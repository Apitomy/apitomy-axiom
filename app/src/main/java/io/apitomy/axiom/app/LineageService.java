package io.apitomy.axiom.app;

import io.apitomy.axiom.api.beans.Error;
import io.apitomy.axiom.api.beans.LineageEdge;
import io.apitomy.axiom.api.beans.LineageGraph;
import io.apitomy.axiom.api.beans.LineageNode;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the lineage graph of an entity (#430): the work it came from (upstream) and the work it
 * produced (downstream), across stream events, traces, workflow runs, tasks, scheduled job runs and
 * reports.
 *
 * <p>The walk is breadth-first and batched: each level loads the frontier and its links with a fixed
 * number of {@code IN} queries per entity type (split into chunks of {@value #IN_CHUNK} IDs), so the
 * number of queries depends on the depth, not on the number of nodes. Expansion queries are limited to
 * the room left under {@code maxNodes} (plus one row, to detect truncation). Events, tasks, workflow
 * runs, job runs and reports are read as label projections, never as full rows. AI cost is aggregated
 * at the end with one grouped query per cost key. See {@code docs/developer-guide/lineage.md}.</p>
 */
@ApplicationScoped
public class LineageService {

    /** Root entity and node type: stream event. */
    public static final String EVENT = "event";
    /** Root entity and node type: trace not owned by a run, report or job run. */
    public static final String TRACE = "trace";
    /** Root entity and node type: workflow run. */
    public static final String WORKFLOW_RUN = "workflow-run";
    /** Root entity and node type: task. */
    public static final String TASK = "task";
    /** Root entity and node type: scheduled job run. */
    public static final String JOB_RUN = "scheduled-job-run";
    /** Root entity and node type: report. */
    public static final String REPORT = "report";
    /** Node type: routing outcome item that is not a task or a workflow run. */
    public static final String OUTCOME = "outcome";

    /** Default number of hops walked in each direction. */
    public static final int DEFAULT_DEPTH = 5;
    /** Maximum allowed depth. */
    public static final int MAX_DEPTH = 10;
    /** Default node limit. */
    public static final int DEFAULT_MAX_NODES = 200;
    /** Maximum allowed node limit. */
    public static final int MAX_MAX_NODES = 1000;
    /** Maximum number of IDs in one {@code IN} list. */
    public static final int IN_CHUNK = 500;

    static final String UP = "upstream";
    static final String DOWN = "downstream";
    static final String BOTH = "both";

    private static final int UNBOUNDED = Integer.MAX_VALUE;

    private static final Set<String> ROOT_TYPES = Set.of(EVENT, TRACE, WORKFLOW_RUN, TASK, JOB_RUN, REPORT);

    /** Trace types whose trace is represented by the entity that owns it. */
    private static final Map<String, String> OWNED_TRACE_TYPES = Map.of(
            "workflow", WORKFLOW_RUN,
            "scheduled-job-execution", JOB_RUN,
            "report-generation", REPORT);

    private static final String EVENT_COLS =
            "select e.id, e.type, e.ref, e.source, e.createdOn from StreamEventEntity e";
    private static final String TASK_COLS = "select t.id, t.actionType, t.status, t.createdOn, t.traceId,"
            + " t.eventId, t.workflowRunId from TaskEntity t";
    private static final String RUN_COLS =
            "select r.id, r.status, r.startedOn, r.traceId, r.triggerEventId from WorkflowRunEntity r";
    private static final String JOB_RUN_COLS = "select r.id, r.jobId, r.status, r.trigger, r.createdOn,"
            + " r.traceId, r.triggeredByTraceId from ScheduledJobRunEntity r";
    private static final String REPORT_COLS = "select r.id, r.title, r.status, r.trigger, r.createdOn,"
            + " r.traceId, r.triggeredByTraceId from ReportEntity r";

    @Inject
    EntityManager em;

    /**
     * Builds the lineage graph of an entity.
     *
     * @param entityType root entity type ({@code event}, {@code trace}, {@code workflow-run}, {@code task},
     *                   {@code scheduled-job-run} or {@code report})
     * @param id         root entity ID (UUID for events and traces, numeric otherwise)
     * @param direction  {@code upstream}, {@code downstream} or {@code both}; null means both
     * @param depth      maximum number of hops in each direction (1 to {@link #MAX_DEPTH})
     * @param maxNodes   maximum number of nodes (1 to {@link #MAX_MAX_NODES})
     * @return the lineage graph
     * @throws WebApplicationException 400 for an invalid argument, 404 if the root does not exist; both
     *                                 with a JSON {@link Error} body
     */
    @Transactional
    public LineageGraph getLineage(String entityType, String id, String direction, int depth, int maxNodes) {
        String dir = direction == null || direction.isBlank() ? BOTH : direction;
        if (!Set.of(UP, DOWN, BOTH).contains(dir)) {
            throw error(400, "Invalid direction: " + direction);
        }
        if (depth < 1 || depth > MAX_DEPTH) {
            throw error(400, "depth must be between 1 and " + MAX_DEPTH);
        }
        if (maxNodes < 1 || maxNodes > MAX_MAX_NODES) {
            throw error(400, "maxNodes must be between 1 and " + MAX_MAX_NODES);
        }
        Ref root = parseRoot(entityType, id);

        Walk walk = new Walk(maxNodes);
        walk.load(List.of(root));
        if (walk.entity(root) == null) {
            throw error(404, "Not found: " + entityType + " " + id);
        }
        walk.addNode(root, "root", 0);
        if (!DOWN.equals(dir)) {
            walk.walk(root, true, depth);
        }
        if (!UP.equals(dir)) {
            walk.walk(root, false, depth);
        }
        walk.load(walk.nodes.values().stream().map(Placed::ref).toList());
        return walk.toGraph(root, depth);
    }

    /**
     * Builds an exception with a JSON {@link Error} body.
     *
     * @param status  HTTP status
     * @param message error message
     * @return the exception to throw
     */
    public static WebApplicationException error(int status, String message) {
        Error body = new Error();
        body.setMessage(message);
        body.setErrorCode(status);
        return new WebApplicationException(message, Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE).entity(body).build());
    }

    private static Ref parseRoot(String entityType, String id) {
        if (entityType == null || !ROOT_TYPES.contains(entityType)) {
            throw error(400, "Unknown entity type: " + entityType);
        }
        try {
            if (EVENT.equals(entityType) || TRACE.equals(entityType)) {
                return new Ref(entityType, UUID.fromString(id).toString());
            }
            return new Ref(entityType, Long.toString(Long.parseLong(id)));
        } catch (IllegalArgumentException e) {
            throw error(400, "Invalid " + entityType + " ID: " + id);
        }
    }

    /** A node reference: entity type and ID. */
    record Ref(String type, String id) {
        String key() {
            return type + ":" + id;
        }

        UUID uuid() {
            return UUID.fromString(id);
        }

        Long longId() {
            return Long.valueOf(id);
        }

        static Ref of(String type, Object id) {
            return new Ref(type, String.valueOf(id));
        }
    }

    /** A link from an upstream node to a downstream node. */
    record Link(Ref from, Ref to, String relation) {
    }

    /** A node placed in the graph. */
    record Placed(Ref ref, String direction, int depth) {
    }

    /** Label columns of a stream event. */
    record EventInfo(UUID id, String type, String ref, String source, Instant createdOn) {
        static EventInfo of(Object[] r) {
            return new EventInfo((UUID) r[0], (String) r[1], (String) r[2], (String) r[3], (Instant) r[4]);
        }
    }

    /** Label and link columns of a task. */
    record TaskInfo(Long id, String actionType, String status, Instant createdOn, UUID traceId, UUID eventId,
            Long workflowRunId) {
        static TaskInfo of(Object[] r) {
            return new TaskInfo((Long) r[0], (String) r[1], (String) r[2], (Instant) r[3], (UUID) r[4],
                    (UUID) r[5], (Long) r[6]);
        }
    }

    /** Label and link columns of a workflow run. */
    record RunInfo(Long id, String status, Instant startedOn, UUID traceId, UUID triggerEventId) {
        static RunInfo of(Object[] r) {
            return new RunInfo((Long) r[0], (String) r[1], (Instant) r[2], (UUID) r[3], (UUID) r[4]);
        }
    }

    /** Label and link columns of a scheduled job run. */
    record JobRunInfo(Long id, Long jobId, String status, String trigger, Instant createdOn, UUID traceId,
            UUID triggeredByTraceId) {
        static JobRunInfo of(Object[] r) {
            return new JobRunInfo((Long) r[0], (Long) r[1], (String) r[2], (String) r[3], (Instant) r[4],
                    (UUID) r[5], (UUID) r[6]);
        }
    }

    /** Label and link columns of a report. */
    record ReportInfo(Long id, String title, String status, String trigger, Instant createdOn, UUID traceId,
            UUID triggeredByTraceId) {
        static ReportInfo of(Object[] r) {
            return new ReportInfo((Long) r[0], (String) r[1], (String) r[2], (String) r[3], (Instant) r[4],
                    (UUID) r[5], (UUID) r[6]);
        }
    }

    /** Link columns of a routing outcome. */
    record OutcomeInfo(Long id, Long ledgerId, UUID traceId) {
    }

    /** State of one lineage walk. */
    private final class Walk {
        final int maxNodes;
        final Map<String, Placed> nodes = new LinkedHashMap<>();
        final Map<String, LineageEdge> edges = new LinkedHashMap<>();
        /** Loaded rows by node key; a null value means the row does not exist. */
        final Map<String, Object> entities = new HashMap<>();
        final Map<UUID, Ref> canonicalTraces = new HashMap<>();
        final Map<Long, String> jobNames = new HashMap<>();
        boolean truncated;

        Walk(int maxNodes) {
            this.maxNodes = maxNodes;
        }

        Object entity(Ref ref) {
            return entities.get(ref.key());
        }

        /** Row limit for an expansion query: the room left in the graph, plus one to detect truncation. */
        int expansionLimit() {
            return Math.max(0, maxNodes - nodes.size()) + 1;
        }

        boolean addNode(Ref ref, String direction, int depth) {
            if (nodes.containsKey(ref.key())) {
                return false;
            }
            if (nodes.size() >= maxNodes) {
                truncated = true;
                return false;
            }
            nodes.put(ref.key(), new Placed(ref, direction, depth));
            return true;
        }

        void addEdge(Link link) {
            if (link.from() == null || link.to() == null || link.from().equals(link.to())
                    || !nodes.containsKey(link.from().key()) || !nodes.containsKey(link.to().key())) {
                return;
            }
            String key = link.from().key() + ">" + link.to().key();
            if (!edges.containsKey(key)) {
                LineageEdge edge = new LineageEdge();
                edge.setFrom(link.from().key());
                edge.setTo(link.to().key());
                edge.setRelation(link.relation());
                edges.put(key, edge);
            }
        }

        void walk(Ref root, boolean upstream, int depth) {
            String direction = upstream ? UP : DOWN;
            List<Ref> frontier = List.of(root);
            for (int level = 1; level <= depth && !frontier.isEmpty(); level++) {
                load(frontier);
                List<Link> links = upstream ? expandUp(frontier) : expandDown(frontier);
                frontier = place(links, upstream, direction, level);
            }
            if (!frontier.isEmpty()) {
                // Depth limit reached: keep edges between nodes already in the graph and check whether
                // anything lies beyond the limit.
                load(frontier);
                List<Link> beyond = upstream ? expandUp(frontier) : expandDown(frontier);
                beyond.forEach(this::addEdge);
                if (beyond.stream().map(l -> upstream ? l.from() : l.to())
                        .anyMatch(r -> r != null && !nodes.containsKey(r.key()))) {
                    truncated = true;
                }
            }
        }

        /**
         * Adds the new end of each link, but only when its known end is already in the graph, so no node
         * is left without an edge. Links whose known end is itself new in this level (a task created by
         * a sibling task) are retried until nothing more can be placed.
         */
        List<Ref> place(List<Link> links, boolean upstream, String direction, int level) {
            List<Ref> next = new ArrayList<>();
            List<Link> pending = new ArrayList<>(links);
            boolean progress = true;
            while (progress && !pending.isEmpty()) {
                progress = false;
                List<Link> waiting = new ArrayList<>();
                for (Link link : pending) {
                    Ref known = upstream ? link.to() : link.from();
                    Ref other = upstream ? link.from() : link.to();
                    if (known == null || other == null) {
                        continue;
                    }
                    if (!nodes.containsKey(known.key())) {
                        waiting.add(link);
                        continue;
                    }
                    if (addNode(other, direction, level)) {
                        next.add(other);
                        progress = true;
                    }
                    addEdge(link);
                }
                pending = waiting;
            }
            return next;
        }

        // ── Queries ─────────────────────────────────────────────────

        /**
         * Runs a query with an {@code :ids} parameter, splitting the IDs into chunks of
         * {@value #IN_CHUNK} and stopping once {@code limit} rows were read. Reaching a bounded limit
         * marks the graph as truncated.
         */
        <T> List<T> query(Class<T> type, String jpql, Collection<?> ids, int limit, Map<String, Object> params) {
            List<T> out = new ArrayList<>();
            List<?> all = ids.stream().filter(Objects::nonNull).distinct().toList();
            for (int i = 0; i < all.size() && out.size() < limit; i += IN_CHUNK) {
                TypedQuery<T> q = em.createQuery(jpql, type)
                        .setParameter("ids", all.subList(i, Math.min(all.size(), i + IN_CHUNK)));
                params.forEach(q::setParameter);
                if (limit != UNBOUNDED) {
                    q.setMaxResults(limit - out.size());
                }
                out.addAll(q.getResultList());
            }
            if (limit != UNBOUNDED && out.size() >= limit) {
                truncated = true;
            }
            return out;
        }

        <T> List<T> query(Class<T> type, String jpql, Collection<?> ids, int limit) {
            return query(type, jpql, ids, limit, Map.of());
        }

        <T> List<T> rows(String jpql, Collection<?> ids, int limit, Function<Object[], T> mapper) {
            return query(Object[].class, jpql, ids, limit).stream().map(mapper).toList();
        }

        // ── Loading ─────────────────────────────────────────────────

        void load(Collection<Ref> refs) {
            Map<String, List<Ref>> byType = refs.stream()
                    .filter(r -> !entities.containsKey(r.key()))
                    .distinct()
                    .collect(Collectors.groupingBy(Ref::type));
            byType.forEach((type, list) -> {
                List<?> rows = switch (type) {
                    case EVENT -> rows(EVENT_COLS + " where e.id in :ids", uuids(list), UNBOUNDED,
                            EventInfo::of);
                    case TRACE -> query(TraceEntity.class, "from TraceEntity where traceId in :ids", uuids(list),
                            UNBOUNDED);
                    case WORKFLOW_RUN -> rows(RUN_COLS + " where r.id in :ids", longs(list), UNBOUNDED,
                            RunInfo::of);
                    case TASK -> rows(TASK_COLS + " where t.id in :ids", longs(list), UNBOUNDED, TaskInfo::of);
                    case JOB_RUN -> rows(JOB_RUN_COLS + " where r.id in :ids", longs(list), UNBOUNDED,
                            JobRunInfo::of);
                    case REPORT -> rows(REPORT_COLS + " where r.id in :ids", longs(list), UNBOUNDED,
                            ReportInfo::of);
                    case OUTCOME -> query(RoutingOutcomeItemEntity.class,
                            "from RoutingOutcomeItemEntity where id in :ids", longs(list), UNBOUNDED);
                    default -> List.of();
                };
                list.forEach(r -> entities.putIfAbsent(r.key(), null));
                rows.forEach(this::remember);
            });
            loadJobNames();
        }

        /** Stores a loaded row under its node key and returns its reference. */
        Ref remember(Object row) {
            Ref ref = refOf(row);
            entities.put(ref.key(), row);
            return ref;
        }

        void loadJobNames() {
            Set<Long> jobIds = entities.values().stream()
                    .filter(JobRunInfo.class::isInstance)
                    .map(e -> ((JobRunInfo) e).jobId())
                    .filter(j -> !jobNames.containsKey(j))
                    .collect(Collectors.toSet());
            if (!jobIds.isEmpty()) {
                query(Object[].class, "select j.id, j.name from ScheduledJobEntity j where j.id in :ids", jobIds,
                        UNBOUNDED).forEach(r -> jobNames.put((Long) r[0], (String) r[1]));
                jobIds.forEach(j -> jobNames.putIfAbsent(j, null));
            }
        }

        /**
         * Maps trace IDs to the node that represents them: the owning workflow run, scheduled job run
         * or report for owned trace types, otherwise the trace itself (also when the trace is missing).
         */
        Map<UUID, Ref> canonical(Collection<UUID> traceIds) {
            Set<UUID> todo = traceIds.stream().filter(Objects::nonNull)
                    .filter(t -> !canonicalTraces.containsKey(t)).collect(Collectors.toSet());
            if (!todo.isEmpty()) {
                load(todo.stream().map(t -> Ref.of(TRACE, t)).toList());
                Map<String, Set<UUID>> owned = new HashMap<>();
                for (UUID t : todo) {
                    canonicalTraces.put(t, Ref.of(TRACE, t));
                    TraceEntity trace = (TraceEntity) entities.get(Ref.of(TRACE, t).key());
                    if (trace != null && OWNED_TRACE_TYPES.containsKey(trace.traceType)) {
                        owned.computeIfAbsent(OWNED_TRACE_TYPES.get(trace.traceType), k -> new HashSet<>()).add(t);
                    }
                }
                owned.forEach((type, ids) -> {
                    List<?> owners = switch (type) {
                        case WORKFLOW_RUN -> rows(RUN_COLS + " where r.traceId in :ids", ids, UNBOUNDED,
                                RunInfo::of);
                        case JOB_RUN -> rows(JOB_RUN_COLS + " where r.traceId in :ids", ids, UNBOUNDED,
                                JobRunInfo::of);
                        default -> rows(REPORT_COLS + " where r.traceId in :ids", ids, UNBOUNDED, ReportInfo::of);
                    };
                    owners.forEach(o -> canonicalTraces.put(traceIdOf(o), remember(o)));
                });
                loadJobNames();
            }
            Map<UUID, Ref> result = new HashMap<>();
            traceIds.stream().filter(Objects::nonNull).forEach(t -> result.put(t, canonicalTraces.get(t)));
            return result;
        }

        @SuppressWarnings("unchecked")
        <T> List<T> loaded(List<Ref> refs, String type) {
            return refs.stream().filter(r -> type.equals(r.type()))
                    .map(r -> (T) entity(r)).filter(Objects::nonNull).toList();
        }

        // ── Upstream ────────────────────────────────────────────────

        List<Link> expandUp(List<Ref> frontier) {
            List<Link> links = new ArrayList<>();

            List<TaskInfo> tasks = loaded(frontier, TASK);
            List<TaskInfo> unlinked = tasks.stream().filter(t -> t.workflowRunId() == null).toList();
            Map<Long, Long> parents = parentTasks(unlinked.stream().map(TaskInfo::id).toList());
            Map<UUID, Ref> taskTraces = canonical(unlinked.stream().filter(t -> !parents.containsKey(t.id()))
                    .map(TaskInfo::traceId).filter(Objects::nonNull).toList());
            for (TaskInfo task : tasks) {
                Ref ref = Ref.of(TASK, task.id());
                if (task.workflowRunId() != null) {
                    links.add(new Link(Ref.of(WORKFLOW_RUN, task.workflowRunId()), ref, "created"));
                } else if (parents.containsKey(task.id())) {
                    links.add(new Link(Ref.of(TASK, parents.get(task.id())), ref, "created"));
                } else if (task.traceId() != null) {
                    links.add(new Link(taskTraces.get(task.traceId()), ref, "created"));
                } else if (task.eventId() != null) {
                    links.add(new Link(Ref.of(EVENT, task.eventId()), ref, "created"));
                }
            }

            List<TraceEntity> traces = loaded(frontier, TRACE);
            Map<UUID, Ref> owners = canonical(traces.stream().map(t -> t.traceId).toList());
            for (TraceEntity trace : traces) {
                Ref ref = Ref.of(TRACE, trace.traceId);
                Ref owner = owners.get(trace.traceId);
                if (!ref.equals(owner)) {
                    links.add(new Link(owner, ref, "part-of"));
                } else if (trace.eventId != null) {
                    links.add(new Link(Ref.of(EVENT, trace.eventId), ref, "triggered"));
                }
            }

            List<RunInfo> runs = loaded(frontier, WORKFLOW_RUN);
            for (RunInfo run : runs) {
                if (run.triggerEventId() != null) {
                    links.add(new Link(Ref.of(EVENT, run.triggerEventId()), Ref.of(WORKFLOW_RUN, run.id()),
                            "triggered"));
                }
            }
            if (!runs.isEmpty()) {
                query(Object[].class, "select r.runId, r.eventId from WorkflowRunResumeEntity r"
                        + " where r.runId in :ids order by r.resumedOn", runs.stream().map(RunInfo::id).toList(),
                        expansionLimit())
                        .forEach(r -> links.add(new Link(Ref.of(EVENT, r[1]), Ref.of(WORKFLOW_RUN, r[0]),
                                "resumed")));
            }

            Map<Ref, UUID> callers = new LinkedHashMap<>();
            this.<JobRunInfo>loaded(frontier, JOB_RUN).stream()
                    .filter(r -> r.triggeredByTraceId() != null)
                    .forEach(r -> callers.put(Ref.of(JOB_RUN, r.id()), r.triggeredByTraceId()));
            this.<ReportInfo>loaded(frontier, REPORT).stream()
                    .filter(r -> r.triggeredByTraceId() != null)
                    .forEach(r -> callers.put(Ref.of(REPORT, r.id()), r.triggeredByTraceId()));
            Map<UUID, Ref> callerRefs = canonical(callers.values());
            callers.forEach((ref, trace) -> links.add(new Link(callerRefs.get(trace), ref, "triggered-by-agent")));
            return links;
        }

        /**
         * Returns, for each task, the task whose trace node is the parent of the task's trace node
         * (a task created by an agent task that joined its trace).
         */
        Map<Long, Long> parentTasks(Collection<Long> taskIds) {
            Map<Long, Long> result = new HashMap<>();
            if (taskIds.isEmpty()) {
                return result;
            }
            List<TraceNodeEntity> taskNodes = query(TraceNodeEntity.class,
                    "from TraceNodeEntity where entityType = :type and entityId in :ids",
                    taskIds.stream().map(String::valueOf).toList(), UNBOUNDED, Map.of("type", TASK));
            Set<Long> parentIds = taskNodes.stream().map(n -> n.parentNodeId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (parentIds.isEmpty()) {
                return result;
            }
            Map<Long, TraceNodeEntity> parentNodes = query(TraceNodeEntity.class,
                    "from TraceNodeEntity where id in :ids", parentIds, UNBOUNDED)
                    .stream().collect(Collectors.toMap(n -> n.id, Function.identity()));
            for (TraceNodeEntity node : taskNodes) {
                TraceNodeEntity parent = node.parentNodeId == null ? null : parentNodes.get(node.parentNodeId);
                Long parentTask = parent != null && TASK.equals(parent.entityType) ? parseLong(parent.entityId) : null;
                Long taskId = parseLong(node.entityId);
                if (parentTask != null && taskId != null && !parentTask.equals(taskId)) {
                    result.put(taskId, parentTask);
                }
            }
            return result;
        }

        // ── Downstream ──────────────────────────────────────────────

        List<Link> expandDown(List<Ref> frontier) {
            List<Link> links = new ArrayList<>();
            /* Trace ID → node that owns the work done inside the trace. */
            Map<UUID, Ref> traceOwners = new LinkedHashMap<>();

            List<EventInfo> events = loaded(frontier, EVENT);
            if (!events.isEmpty()) {
                expandEvents(events.stream().map(EventInfo::id).toList(), links);
            }

            List<TraceEntity> traces = loaded(frontier, TRACE);
            traces.forEach(t -> traceOwners.put(t.traceId, Ref.of(TRACE, t.traceId)));
            if (!traces.isEmpty()) {
                expandTraceOutcomes(traces.stream().map(t -> t.traceId).toList(), links);
            }

            List<RunInfo> runs = loaded(frontier, WORKFLOW_RUN);
            runs.stream().filter(r -> r.traceId() != null)
                    .forEach(r -> traceOwners.putIfAbsent(r.traceId(), Ref.of(WORKFLOW_RUN, r.id())));
            if (!runs.isEmpty()) {
                rows(TASK_COLS + " where t.workflowRunId in :ids order by t.id",
                        runs.stream().map(RunInfo::id).toList(), expansionLimit(), TaskInfo::of)
                        .forEach(t -> links.add(new Link(Ref.of(WORKFLOW_RUN, t.workflowRunId()), remember(t),
                                "created")));
            }
            this.<JobRunInfo>loaded(frontier, JOB_RUN).stream().filter(r -> r.traceId() != null)
                    .forEach(r -> traceOwners.putIfAbsent(r.traceId(), Ref.of(JOB_RUN, r.id())));
            this.<ReportInfo>loaded(frontier, REPORT).stream().filter(r -> r.traceId() != null)
                    .forEach(r -> traceOwners.putIfAbsent(r.traceId(), Ref.of(REPORT, r.id())));
            if (!traceOwners.isEmpty()) {
                expandTraceWork(traceOwners, links);
            }

            List<TaskInfo> tasks = loaded(frontier, TASK);
            if (!tasks.isEmpty()) {
                expandChildTasks(tasks.stream().map(TaskInfo::id).toList(), links);
            }
            return links;
        }

        /** Event → traces, workflow runs, resumed runs and routing outcome items. */
        void expandEvents(List<UUID> eventIds, List<Link> links) {
            List<TraceEntity> traces = query(TraceEntity.class,
                    "from TraceEntity where eventId in :ids order by startedOn", eventIds, expansionLimit());
            traces.forEach(this::remember);
            Map<UUID, Ref> canon = canonical(traces.stream().map(t -> t.traceId).toList());
            traces.forEach(t -> links.add(new Link(Ref.of(EVENT, t.eventId), canon.get(t.traceId), "triggered")));

            rows(RUN_COLS + " where r.triggerEventId in :ids order by r.id", eventIds, expansionLimit(), RunInfo::of)
                    .forEach(r -> links.add(new Link(Ref.of(EVENT, r.triggerEventId()), remember(r), "triggered")));
            query(Object[].class, "select r.eventId, r.runId from WorkflowRunResumeEntity r"
                    + " where r.eventId in :ids order by r.resumedOn", eventIds, expansionLimit())
                    .forEach(r -> links.add(new Link(Ref.of(EVENT, r[0]), Ref.of(WORKFLOW_RUN, r[1]), "resumed")));

            Map<Long, UUID> ledgerEvents = new HashMap<>();
            query(Object[].class, "select l.id, l.eventId from EventProcessingLedgerEntity l where l.eventId in :ids",
                    eventIds, UNBOUNDED).forEach(r -> ledgerEvents.put((Long) r[0], (UUID) r[1]));
            if (ledgerEvents.isEmpty()) {
                return;
            }
            Map<Long, OutcomeInfo> outcomes = new HashMap<>();
            query(Object[].class, "select o.id, o.ledgerId, o.traceId from RoutingOutcomeEntity o"
                    + " where o.ledgerId in :ids", ledgerEvents.keySet(), UNBOUNDED)
                    .forEach(r -> outcomes.put((Long) r[0], new OutcomeInfo((Long) r[0], (Long) r[1], (UUID) r[2])));
            if (outcomes.isEmpty()) {
                return;
            }
            List<RoutingOutcomeItemEntity> items = query(RoutingOutcomeItemEntity.class,
                    "from RoutingOutcomeItemEntity where outcomeId in :ids order by id", outcomes.keySet(),
                    expansionLimit());
            for (RoutingOutcomeItemEntity item : items) {
                OutcomeInfo outcome = outcomes.get(item.outcomeId);
                Ref event = Ref.of(EVENT, ledgerEvents.get(outcome.ledgerId()));
                if (RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN.equals(item.itemType) && item.workflowRunId != null) {
                    links.add(new Link(event, Ref.of(WORKFLOW_RUN, item.workflowRunId), "triggered"));
                } else if (RoutingOutcomeItemEntity.TYPE_WORKFLOW_RESUMED.equals(item.itemType)
                        && item.workflowRunId != null) {
                    links.add(new Link(event, Ref.of(WORKFLOW_RUN, item.workflowRunId), "resumed"));
                } else if (outcome.traceId() == null) {
                    // Outcomes with a trace are attached to that trace's node instead.
                    links.add(itemLink(event, item));
                }
            }
        }

        /** Trace → routing outcome items recorded for the trace (Manager decisions). */
        void expandTraceOutcomes(List<UUID> traceIds, List<Link> links) {
            Map<Long, UUID> outcomeTraces = new HashMap<>();
            query(Object[].class, "select o.id, o.traceId from RoutingOutcomeEntity o where o.traceId in :ids",
                    traceIds, UNBOUNDED).forEach(r -> outcomeTraces.put((Long) r[0], (UUID) r[1]));
            if (outcomeTraces.isEmpty()) {
                return;
            }
            query(RoutingOutcomeItemEntity.class, "from RoutingOutcomeItemEntity where outcomeId in :ids"
                    + " and itemType not in :runTypes order by id", outcomeTraces.keySet(), expansionLimit(),
                    Map.of("runTypes", List.of(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN,
                            RoutingOutcomeItemEntity.TYPE_WORKFLOW_RESUMED)))
                    .forEach(i -> links.add(itemLink(Ref.of(TRACE, outcomeTraces.get(i.outcomeId)), i)));
        }

        Link itemLink(Ref from, RoutingOutcomeItemEntity item) {
            if (RoutingOutcomeItemEntity.TYPE_TASK.equals(item.itemType) && item.taskId != null) {
                return new Link(from, Ref.of(TASK, item.taskId), "created");
            }
            return new Link(from, remember(item), "produced");
        }

        /**
         * Work done inside traces: tasks that ran in (or joined) the trace, and job runs and reports
         * that an agent in the trace triggered.
         */
        void expandTraceWork(Map<UUID, Ref> traceOwners, List<Link> links) {
            List<TaskInfo> tasks = rows(TASK_COLS + " where t.traceId in :ids order by t.id", traceOwners.keySet(),
                    expansionLimit(), TaskInfo::of);
            tasks.forEach(this::remember);
            Map<Long, Long> parents = parentTasks(tasks.stream().map(TaskInfo::id).toList());
            for (TaskInfo task : tasks) {
                Long parent = parents.get(task.id());
                Ref from = parent != null ? Ref.of(TASK, parent) : traceOwners.get(task.traceId());
                links.add(new Link(from, Ref.of(TASK, task.id()), "created"));
            }
            rows(JOB_RUN_COLS + " where r.triggeredByTraceId in :ids order by r.id", traceOwners.keySet(),
                    expansionLimit(), JobRunInfo::of)
                    .forEach(r -> links.add(new Link(traceOwners.get(r.triggeredByTraceId()), remember(r),
                            "triggered-by-agent")));
            rows(REPORT_COLS + " where r.triggeredByTraceId in :ids order by r.id", traceOwners.keySet(),
                    expansionLimit(), ReportInfo::of)
                    .forEach(r -> links.add(new Link(traceOwners.get(r.triggeredByTraceId()), remember(r),
                            "triggered-by-agent")));
            loadJobNames();
        }

        /** Task → tasks created by its agent (task nodes under the task's trace node). */
        void expandChildTasks(List<Long> taskIds, List<Link> links) {
            List<TraceNodeEntity> taskNodes = query(TraceNodeEntity.class,
                    "from TraceNodeEntity where entityType = :type and entityId in :ids",
                    taskIds.stream().map(String::valueOf).toList(), UNBOUNDED, Map.of("type", TASK));
            Map<Long, Long> nodeTasks = new HashMap<>();
            for (TraceNodeEntity node : taskNodes) {
                Long taskId = parseLong(node.entityId);
                if (taskId != null) {
                    nodeTasks.putIfAbsent(node.id, taskId);
                }
            }
            if (nodeTasks.isEmpty()) {
                return;
            }
            query(TraceNodeEntity.class, "from TraceNodeEntity where parentNodeId in :ids and entityType = :type"
                    + " order by id", nodeTasks.keySet(), expansionLimit(), Map.of("type", TASK))
                    .forEach(child -> {
                        Long childTask = parseLong(child.entityId);
                        if (childTask != null) {
                            links.add(new Link(Ref.of(TASK, nodeTasks.get(child.parentNodeId)),
                                    Ref.of(TASK, childTask), "created"));
                        }
                    });
        }

        // ── Output ──────────────────────────────────────────────────

        LineageGraph toGraph(Ref root, int depth) {
            Map<String, Double> costs = costs();
            List<LineageNode> out = new ArrayList<>();
            double total = 0;
            for (Placed placed : nodes.values()) {
                LineageNode node = describe(placed.ref());
                node.setKey(placed.ref().key());
                node.setType(placed.ref().type());
                node.setId(placed.ref().id());
                node.setDirection(placed.direction());
                node.setDepth(placed.depth());
                if (node.getAvailable() && costs.containsKey(node.getKey())) {
                    node.setCostUsd(costs.get(node.getKey()));
                    total += node.getCostUsd();
                }
                out.add(node);
            }
            LineageGraph graph = new LineageGraph();
            graph.setRoot(root.key());
            graph.setNodes(out);
            graph.setEdges(new ArrayList<>(edges.values()));
            graph.setTruncated(truncated);
            graph.setDepth(depth);
            graph.setMaxNodes(maxNodes);
            graph.setTotalCostUsd(total);
            return graph;
        }

        /**
         * Direct AI cost per node key, with one grouped query per cost key. Usage rows of a trace that
         * belong to a task, job run or report are counted on that entity, not on the trace.
         */
        Map<String, Double> costs() {
            Map<String, Double> costs = new HashMap<>();
            Map<String, List<Long>> ids = new HashMap<>();
            Map<UUID, String> traceKeys = new HashMap<>();
            for (Placed placed : nodes.values()) {
                Object entity = entity(placed.ref());
                if (entity == null) {
                    continue;
                }
                String type = placed.ref().type();
                switch (type) {
                    case TASK, JOB_RUN, REPORT -> ids.computeIfAbsent(type, k -> new ArrayList<>())
                            .add(placed.ref().longId());
                    case TRACE -> traceKeys.put(placed.ref().uuid(), placed.ref().key());
                    case WORKFLOW_RUN -> {
                        costs.put(placed.ref().key(), 0.0);
                        UUID trace = ((RunInfo) entity).traceId();
                        if (trace != null) {
                            traceKeys.putIfAbsent(trace, placed.ref().key());
                        }
                    }
                    default -> {
                    }
                }
            }
            sumBy("taskId", TASK, ids.get(TASK), costs);
            sumBy("scheduledJobRunId", JOB_RUN, ids.get(JOB_RUN), costs);
            sumBy("reportId", REPORT, ids.get(REPORT), costs);
            if (!traceKeys.isEmpty()) {
                traceKeys.values().forEach(k -> costs.put(k, 0.0));
                query(Object[].class, "select u.traceId, sum(u.costUsd) from AiUsageEntity u"
                        + " where u.traceId in :ids and u.taskId is null and u.scheduledJobRunId is null"
                        + " and u.reportId is null group by u.traceId", traceKeys.keySet(), UNBOUNDED)
                        .forEach(r -> costs.put(traceKeys.get((UUID) r[0]), toDouble(r[1])));
            }
            return costs;
        }

        void sumBy(String column, String type, List<Long> ids, Map<String, Double> costs) {
            if (ids == null || ids.isEmpty()) {
                return;
            }
            ids.forEach(i -> costs.put(Ref.of(type, i).key(), 0.0));
            query(Object[].class, "select u." + column + ", sum(u.costUsd) from AiUsageEntity u"
                    + " where u." + column + " in :ids group by u." + column, ids, UNBOUNDED)
                    .forEach(r -> costs.put(Ref.of(type, r[0]).key(), toDouble(r[1])));
        }

        LineageNode describe(Ref ref) {
            LineageNode node = new LineageNode();
            Object entity = entity(ref);
            node.setAvailable(entity != null);
            if (entity == null) {
                node.setStatus("unavailable");
                node.setLabel(typeLabel(ref.type()) + " " + ref.id() + " (deleted or unavailable)");
                return node;
            }
            switch (entity) {
                case EventInfo e -> {
                    node.setLabel(e.type() + (e.ref() != null ? " " + e.ref() : ""));
                    node.setSubtype(e.source());
                    node.setLinkPath("/events/stream/" + e.id());
                    node.setCreatedOn(date(e.createdOn()));
                }
                case TraceEntity t -> {
                    node.setLabel(t.summary != null && !t.summary.isBlank() ? t.summary : "Trace " + t.traceType);
                    node.setStatus(t.status);
                    node.setSubtype(t.traceType);
                    node.setLinkPath("/logs/traces/" + t.traceId);
                    node.setCreatedOn(date(t.startedOn));
                }
                case RunInfo r -> {
                    node.setLabel("Workflow run #" + r.id());
                    node.setStatus(r.status());
                    node.setLinkPath("/logs/workflow-runs/" + r.id());
                    node.setCreatedOn(date(r.startedOn()));
                }
                case TaskInfo t -> {
                    node.setLabel("Task #" + t.id() + ": " + t.actionType());
                    node.setStatus(t.status());
                    node.setSubtype(t.actionType());
                    node.setLinkPath("/logs/tasks?taskId=" + t.id());
                    node.setCreatedOn(date(t.createdOn()));
                }
                case JobRunInfo r -> {
                    String job = jobNames.get(r.jobId());
                    node.setLabel((job != null ? job : "Scheduled job " + r.jobId()) + " run #" + r.id());
                    node.setStatus(r.status());
                    node.setSubtype(r.trigger());
                    node.setLinkPath("/logs/job-runs?runId=" + r.id());
                    node.setCreatedOn(date(r.createdOn()));
                }
                case ReportInfo r -> {
                    node.setLabel(r.title() != null && !r.title().isBlank() ? r.title() : "Report #" + r.id());
                    node.setStatus(r.status());
                    node.setSubtype(r.trigger());
                    node.setLinkPath("/reports/" + r.id());
                    node.setCreatedOn(date(r.createdOn()));
                }
                case RoutingOutcomeItemEntity i -> {
                    node.setLabel(i.summary != null && !i.summary.isBlank() ? i.summary : i.itemType);
                    node.setStatus(i.status);
                    node.setSubtype(i.itemType);
                    node.setCreatedOn(date(i.createdOn));
                }
                default -> node.setLabel(ref.key());
            }
            return node;
        }
    }

    private static Ref refOf(Object row) {
        return switch (row) {
            case EventInfo e -> Ref.of(EVENT, e.id());
            case TraceEntity t -> Ref.of(TRACE, t.traceId);
            case RunInfo r -> Ref.of(WORKFLOW_RUN, r.id());
            case TaskInfo t -> Ref.of(TASK, t.id());
            case JobRunInfo r -> Ref.of(JOB_RUN, r.id());
            case ReportInfo r -> Ref.of(REPORT, r.id());
            case RoutingOutcomeItemEntity i -> Ref.of(OUTCOME, i.id);
            default -> throw new IllegalArgumentException("Unsupported lineage row: " + row);
        };
    }

    private static UUID traceIdOf(Object owner) {
        return switch (owner) {
            case RunInfo r -> r.traceId();
            case JobRunInfo r -> r.traceId();
            case ReportInfo r -> r.traceId();
            default -> null;
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case EVENT -> "Event";
            case TRACE -> "Trace";
            case WORKFLOW_RUN -> "Workflow run";
            case TASK -> "Task";
            case JOB_RUN -> "Scheduled job run";
            case REPORT -> "Report";
            default -> "Outcome";
        };
    }

    private static Set<UUID> uuids(List<Ref> refs) {
        return refs.stream().map(Ref::uuid).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<Long> longs(List<Ref> refs) {
        return refs.stream().map(Ref::longId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }
}
