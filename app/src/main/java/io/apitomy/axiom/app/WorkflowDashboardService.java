package io.apitomy.axiom.app;

import io.apitomy.axiom.api.beans.WaitingWorkflowRun;
import io.apitomy.axiom.api.beans.WorkflowRunStatusCounts;
import io.apitomy.axiom.api.beans.WorkflowRunStatusSummary;
import io.apitomy.axiom.api.beans.WorkflowScheduleSummary;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowScheduleTriggerEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only aggregations over workflow runs and schedule triggers used by the dashboards.
 */
@ApplicationScoped
public class WorkflowDashboardService {

    static final String RUNNING = "running";
    static final String WAITING = "waiting";
    static final String COMPLETED = "completed";
    static final String FAILED = "failed";
    static final String CANCELLED = "cancelled";

    @Inject
    WorkflowRunBeanMapper runBeanMapper;

    /**
     * Counts runs per status and lists the most recent failed runs.
     *
     * @param subjectLabels subject labels the runs' subjects must all carry (may be empty)
     * @param since         lower bound on the completion time of terminal runs (nullable)
     * @param failedLimit   maximum number of recent failed runs
     * @return the summary
     */
    public WorkflowRunStatusSummary summarize(List<String> subjectLabels, Instant since, int failedLimit) {
        Map<String, Object> params = new HashMap<>();
        String scope = labelScope("subjectId", subjectLabels, params);

        Map<String, Long> counts = new HashMap<>();
        countByStatus("status in :active" + scope, Map.of("active", List.of(RUNNING, WAITING)), params, counts);
        Map<String, Object> terminalParams = new HashMap<>(Map.of(
                "terminal", List.of(COMPLETED, FAILED, CANCELLED)));
        String terminalWhere = "status in :terminal" + scope;
        if (since != null) {
            terminalWhere += " and completedOn >= :since";
            terminalParams.put("since", since);
        }
        countByStatus(terminalWhere, terminalParams, params, counts);

        WorkflowRunStatusCounts countsBean = new WorkflowRunStatusCounts();
        countsBean.setRunning(counts.getOrDefault(RUNNING, 0L).intValue());
        countsBean.setWaiting(counts.getOrDefault(WAITING, 0L).intValue());
        countsBean.setCompleted(counts.getOrDefault(COMPLETED, 0L).intValue());
        countsBean.setFailed(counts.getOrDefault(FAILED, 0L).intValue());
        countsBean.setCancelled(counts.getOrDefault(CANCELLED, 0L).intValue());

        Map<String, Object> failedParams = new HashMap<>(params);
        failedParams.put("failed", FAILED);
        String failedWhere = "status = :failed" + scope;
        if (since != null) {
            failedWhere += " and completedOn >= :since";
            failedParams.put("since", since);
        }
        List<WorkflowRunEntity> failedRuns = WorkflowRunEntity.<WorkflowRunEntity>find(failedWhere,
                        Sort.descending("completedOn", "id"), failedParams)
                .page(Page.ofSize(failedLimit)).list();

        WorkflowRunStatusSummary summary = new WorkflowRunStatusSummary();
        summary.setCounts(countsBean);
        summary.setRecentFailed(runBeanMapper.toSummaries(failedRuns));
        return summary;
    }

    /**
     * Lists what waiting runs are waiting for: one entry per wait point (wait node, receive-event node or
     * human task awaiting input), longest waiting first.
     *
     * @param subjectLabels subject labels the runs' subjects must all carry (may be empty)
     * @param limit         maximum number of entries
     * @return the waiting entries
     */
    public List<WaitingWorkflowRun> listWaiting(List<String> subjectLabels, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("waiting", WAITING);
        String waitingRuns = "runId in (select r.id from WorkflowRunEntity r where r.status = :waiting"
                + labelScope("r.subjectId", subjectLabels, params) + ")";
        Page page = Page.ofSize(limit);

        List<WaitingWorkflowRun> entries = new ArrayList<>();
        WorkflowWaitEntity.<WorkflowWaitEntity>find(waitingRuns, Sort.ascending("createdOn", "id"), params)
                .page(page).list()
                .forEach(w -> entries.add(entry(w.runId, w.nodeId, WaitingWorkflowRun.WaitKind.WAIT,
                        w.createdOn, w.resumeAt)));
        WorkflowEventSubscriptionEntity.<WorkflowEventSubscriptionEntity>find(waitingRuns,
                        Sort.ascending("createdOn", "id"), params)
                .page(page).list()
                .forEach(s -> {
                    WaitingWorkflowRun entry = entry(s.runId, s.nodeId, WaitingWorkflowRun.WaitKind.RECEIVE_EVENT,
                            s.createdOn, s.timeoutAt);
                    entry.setEventType(s.eventType);
                    entry.setCorrelationKey(s.correlationKey);
                    entries.add(entry);
                });
        Map<String, Object> taskParams = new HashMap<>(params);
        taskParams.put("awaiting", HumanTaskStatuses.AWAITING_INPUT);
        TaskEntity.<TaskEntity>find("status = :awaiting and workflowRunId in (select r.id from WorkflowRunEntity r"
                                + " where r.status = :waiting"
                                + labelScope("r.subjectId", subjectLabels, taskParams) + ")",
                        Sort.ascending("createdOn", "id"), taskParams)
                .page(page).list()
                .forEach(t -> {
                    WaitingWorkflowRun entry = entry(t.workflowRunId, t.nodeId,
                            WaitingWorkflowRun.WaitKind.HUMAN_TASK, t.createdOn, null);
                    entry.setTaskId(t.id);
                    entries.add(entry);
                });

        List<WaitingWorkflowRun> limited = entries.stream()
                .sorted(Comparator.comparing(WaitingWorkflowRun::getWaitingSince)
                        .thenComparing(WaitingWorkflowRun::getRunId))
                .limit(limit)
                .toList();
        enrich(limited);
        return limited;
    }

    /**
     * Lists schedule triggers across all workflow definitions, ordered by next fire time with disabled
     * triggers last.
     *
     * @param limit maximum number of schedules
     * @return the schedules
     */
    public List<WorkflowScheduleSummary> listSchedules(int limit) {
        List<WorkflowScheduleTriggerEntity> triggers = WorkflowScheduleTriggerEntity
                .<WorkflowScheduleTriggerEntity>findAll(Sort.descending("enabled")
                        .and("nextRunAt", Sort.Direction.Ascending, Sort.NullPrecedence.NULLS_LAST)
                        .and("id"))
                .page(Page.ofSize(limit))
                .list();
        Map<Long, Long> lastRunIds = lastStartedRunIds(triggers);
        Map<Long, String> definitionNames = namesById(WorkflowDefinitionEntity.class,
                triggers.stream().map(t -> t.definitionId).collect(Collectors.toSet()),
                (WorkflowDefinitionEntity d) -> d.id, d -> d.name);
        List<String> refs = triggers.stream().map(t -> SubjectEntity.SCHEDULE_REF_PREFIX + t.subjectName)
                .distinct().toList();
        Map<String, SubjectEntity> subjectsByRef = refs.isEmpty() ? Map.of()
                : SubjectEntity.<SubjectEntity>list("ref in ?1", refs).stream()
                        .collect(Collectors.toMap(s -> s.ref, Function.identity()));

        return triggers.stream().map(t -> {
            WorkflowScheduleSummary bean = new WorkflowScheduleSummary();
            bean.setTriggerId(t.id);
            bean.setWorkflowDefinitionId(t.definitionId);
            bean.setWorkflowName(definitionNames.getOrDefault(t.definitionId, "Unknown"));
            bean.setCron(t.cron);
            bean.setEnabled(t.enabled);
            bean.setSubjectName(t.subjectName);
            bean.setNextFireAt(toDate(t.nextRunAt));
            bean.setLastFiredAt(toDate(t.lastRunAt));
            bean.setLastOutcome(toOutcome(t.lastOutcome));
            SubjectEntity subject = subjectsByRef.get(SubjectEntity.SCHEDULE_REF_PREFIX + t.subjectName);
            if (subject != null) {
                bean.setSubjectId(subject.id);
            }
            if (bean.getLastOutcome() == WorkflowScheduleSummary.LastOutcome.STARTED) {
                bean.setLastRunId(lastRunIds.get(t.id));
            }
            return bean;
        }).toList();
    }

    /**
     * Finds, in one query, the run each trigger started at its last firing: the earliest run (lowest id) of
     * the trigger's definition on its schedule subject started at or after the trigger's last run time.
     *
     * @param triggers the triggers
     * @return run id per trigger id, for the triggers where one exists
     */
    private static Map<Long, Long> lastStartedRunIds(List<WorkflowScheduleTriggerEntity> triggers) {
        List<Long> ids = triggers.stream().filter(t -> t.lastRunAt != null).map(t -> t.id).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        WorkflowRunEntity.getEntityManager()
                .createQuery("select t.id, min(r.id) from WorkflowScheduleTriggerEntity t, SubjectEntity s,"
                        + " WorkflowRunEntity r where t.id in :ids and s.ref = concat(:prefix, t.subjectName)"
                        + " and r.subjectId = s.id and r.definitionId = t.definitionId"
                        + " and r.startedOn >= t.lastRunAt group by t.id", Object[].class)
                .setParameter("ids", ids)
                .setParameter("prefix", SubjectEntity.SCHEDULE_REF_PREFIX)
                .getResultList()
                .forEach(row -> result.put((Long) row[0], (Long) row[1]));
        return result;
    }

    /**
     * Maps the outcome recorded by the schedule poller to the API enum.
     *
     * @param outcome the recorded outcome ({@code started}, {@code skipped} or {@code failed: ...})
     * @return the API outcome, or null when none is recorded
     */
    static WorkflowScheduleSummary.LastOutcome toOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return null;
        }
        if (WorkflowScheduleTriggerScheduler.STARTED.equals(outcome)) {
            return WorkflowScheduleSummary.LastOutcome.STARTED;
        }
        if (WorkflowScheduleTriggerScheduler.SKIPPED.equals(outcome)) {
            return WorkflowScheduleSummary.LastOutcome.SKIPPED_DUPLICATE;
        }
        return WorkflowScheduleSummary.LastOutcome.FAILED;
    }

    /**
     * Parses a comma-separated subject label filter.
     *
     * @param filterLabels the comma-separated labels (nullable)
     * @return the trimmed, non-empty labels
     */
    public static List<String> parseLabels(String filterLabels) {
        if (filterLabels == null || filterLabels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(filterLabels.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String labelScope(String subjectIdPath, List<String> labels, Map<String, Object> params) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        params.put("labels", labels);
        params.put("labelCount", (long) labels.size());
        return " and " + subjectIdPath + " in (select p.id from SubjectEntity p join p.labels pl"
                + " where pl in :labels group by p.id having count(distinct pl) = :labelCount)";
    }

    private static void countByStatus(String where, Map<String, Object> extra, Map<String, Object> scopeParams,
                                      Map<String, Long> into) {
        Map<String, Object> params = new HashMap<>(scopeParams);
        params.putAll(extra);
        TypedQuery<Object[]> query = WorkflowRunEntity.getEntityManager()
                .createQuery("select status, count(id) from WorkflowRunEntity where " + where
                        + " group by status", Object[].class);
        params.forEach(query::setParameter);
        query.getResultList().forEach(row -> into.put((String) row[0], ((Number) row[1]).longValue()));
    }

    private static WaitingWorkflowRun entry(Long runId, String nodeId, WaitingWorkflowRun.WaitKind kind,
                                            Instant since, Instant dueAt) {
        WaitingWorkflowRun entry = new WaitingWorkflowRun();
        entry.setRunId(runId);
        entry.setNodeId(nodeId);
        entry.setWaitKind(kind);
        entry.setWaitingSince(toDate(since));
        entry.setDueAt(toDate(dueAt));
        return entry;
    }

    private static void enrich(List<WaitingWorkflowRun> entries) {
        Set<Long> runIds = entries.stream().map(WaitingWorkflowRun::getRunId).collect(Collectors.toSet());
        if (runIds.isEmpty()) {
            return;
        }
        Map<Long, WorkflowRunEntity> runs = WorkflowRunEntity.<WorkflowRunEntity>list("id in ?1", runIds).stream()
                .collect(Collectors.toMap(r -> r.id, Function.identity()));
        Map<Long, String> definitionNames = namesById(WorkflowDefinitionEntity.class,
                runs.values().stream().map(r -> r.definitionId).collect(Collectors.toSet()),
                (WorkflowDefinitionEntity d) -> d.id, d -> d.name);
        Map<Long, String> subjectNames = namesById(SubjectEntity.class,
                runs.values().stream().map(r -> r.subjectId).collect(Collectors.toSet()),
                (SubjectEntity s) -> s.id, s -> s.name);
        for (WaitingWorkflowRun entry : entries) {
            WorkflowRunEntity run = runs.get(entry.getRunId());
            if (run == null) {
                continue;
            }
            entry.setRunTitle(run.title);
            entry.setWorkflowDefinitionId(run.definitionId);
            entry.setWorkflowName(definitionNames.getOrDefault(run.definitionId, "Unknown"));
            entry.setSubjectId(run.subjectId);
            entry.setSubjectName(subjectNames.get(run.subjectId));
        }
    }

    private static <E> Map<Long, String> namesById(Class<E> type, Set<Long> ids, Function<E, Long> idOf,
                                                   Function<E, String> nameOf) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return WorkflowRunEntity.getEntityManager()
                .createQuery("from " + type.getSimpleName() + " where id in :ids", type)
                .setParameter("ids", ids)
                .getResultList().stream()
                .collect(Collectors.toMap(idOf, nameOf, (a, b) -> a));
    }

    private static Date toDate(Instant instant) {
        return instant != null ? Date.from(instant) : null;
    }
}
