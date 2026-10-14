package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.CompositionsResource;
import io.apitomy.axiom.api.beans.AgentChangeSetResult;
import io.apitomy.axiom.api.beans.CompositionTestValues;
import io.apitomy.axiom.api.beans.CompositionTestRun;
import io.apitomy.axiom.api.beans.CompositionTestRequest;
import io.apitomy.axiom.api.beans.AgentTestSuggestion;
import io.apitomy.axiom.api.beans.AgentComposition;
import io.apitomy.axiom.api.beans.AgentItemResult;
import io.apitomy.axiom.api.beans.AgentItemSummary;
import io.apitomy.axiom.api.beans.AgentItemUpsert;
import io.apitomy.axiom.api.beans.AssistantValidateResult;
import io.apitomy.axiom.api.beans.Composition;
import io.apitomy.axiom.api.beans.ChangeSetError;
import io.apitomy.axiom.api.beans.CompositionChangeSetResolution;
import io.apitomy.axiom.api.beans.CompositionContent;
import io.apitomy.axiom.api.beans.CompositionItem;
import io.apitomy.axiom.api.beans.CompositionItemProblems;
import io.apitomy.axiom.api.beans.CompositionItemUpdate;
import io.apitomy.axiom.api.beans.CompositionItemValidateRequest;
import io.apitomy.axiom.api.beans.CompositionMessage;
import io.apitomy.axiom.api.beans.CompositionPublishRequest;
import io.apitomy.axiom.api.beans.CompositionPublishResult;
import io.apitomy.axiom.api.beans.CompositionRevision;
import io.apitomy.axiom.api.beans.CompositionSessionInfo;
import io.apitomy.axiom.api.beans.CompositionUndoResult;
import io.apitomy.axiom.api.beans.CompositionUpdate;
import io.apitomy.axiom.api.beans.CompositionValidationResult;
import io.apitomy.axiom.api.beans.ConfigProblem;
import io.apitomy.axiom.api.beans.NewComposition;
import io.apitomy.axiom.api.beans.NewCompositionItem;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.AssistantSessionManager;
import io.apitomy.axiom.app.assistant.SessionTokenGuard;
import io.apitomy.axiom.app.composition.AgentUnavailableException;
import io.apitomy.axiom.app.composition.CompositionConflictException;
import io.apitomy.axiom.app.composition.CompositionItems;
import io.apitomy.axiom.app.composition.CompositionNotFoundException;
import io.apitomy.axiom.app.composition.CompositionPublisher;
import io.apitomy.axiom.app.composition.CompositionService;
import io.apitomy.axiom.app.composition.CompositionTestRuns;
import io.apitomy.axiom.app.composition.TooManyTestRunsException;
import io.apitomy.axiom.app.composition.CompositionTurns;
import io.apitomy.axiom.app.composition.CompositionValidator;
import io.apitomy.axiom.app.composition.PublishBlockedException;
import io.apitomy.axiom.app.composition.StaleRevisionException;
import io.apitomy.axiom.app.composition.TurnUndo;
import io.apitomy.axiom.app.composition.WorkbenchSessions;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.flow.changeset.ContentRevision;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST implementation of workbench compositions: drafts, revisions, validation, publish, AI turns and the
 * token-guarded agent routes.
 */
@ApplicationScoped
@RunOnVirtualThread
public class CompositionsResourceImpl implements CompositionsResource {

    @Inject
    CompositionService service;

    @Inject
    AssistantSessionManager sessionManager;

    @Inject
    ObjectMapper mapper;

    @Inject
    CompositionValidator validator;

    @Inject
    CompositionPublisher publisher;

    @Inject
    CompositionTurns turns;

    @Inject
    TurnUndo turnUndo;

    @Inject
    WorkbenchSessions workbenchSessions;

    @Inject
    SessionTokenGuard tokenGuard;

    @Inject
    CompositionTestRuns testRuns;

    @Context
    HttpHeaders httpHeaders;

    /** {@inheritDoc} */
    @Override
    public List<Composition> listCompositions() {
        return service.list().stream().map(this::toBean).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Composition createComposition(NewComposition data) {
        return toBean(service.create(data.getName(), data.getGoal(), data.getEngine(), data.getModel(),
                data.getSeedWorkflowId()));
    }

    /** {@inheritDoc} */
    @Override
    public Composition getComposition(long compositionId) {
        return toBean(service.get(compositionId));
    }

    /** {@inheritDoc} */
    @Override
    public Composition updateComposition(long compositionId, CompositionUpdate data) {
        return toBean(service.update(compositionId, data.getName(),
                data.getApplyMode() == null ? null : data.getApplyMode().value()));
    }

    /** {@inheritDoc} */
    @Override
    public void deleteComposition(long compositionId) {
        service.delete(compositionId);
        testRuns.forget(compositionId);
    }

    /** {@inheritDoc} */
    @Override
    public List<CompositionItem> listCompositionItems(long compositionId) {
        return service.items(compositionId).stream().map(i -> toBean(i, false)).toList();
    }

    /** {@inheritDoc} */
    @Override
    public CompositionItem addCompositionItem(long compositionId, NewCompositionItem data) {
        if (data.getType() == null) {
            throw new IllegalArgumentException("type is required");
        }
        ConfigItemType type = CompositionItems.supportedType(data.getType().value());
        CompositionItemEntity item = data.getExistingItemId() != null
                ? service.addExisting(compositionId, type, data.getExistingItemId())
                : service.addBlank(compositionId, type, data.getName(),
                        data.getExecutionMode() == null ? null : data.getExecutionMode().value());
        return toBean(item, true);
    }

    /** {@inheritDoc} */
    @Override
    public CompositionItem getCompositionItem(long compositionId, long itemId) {
        return toBean(service.item(compositionId, itemId), true);
    }

    /** {@inheritDoc} */
    @Override
    public CompositionItem updateCompositionItem(long compositionId, long itemId, CompositionItemUpdate data) {
        JsonNode content = data.getContent() == null ? null
                : mapper.valueToTree(data.getContent().getAdditionalProperties());
        String turnId = data.getTurnId() == null || data.getTurnId().isBlank() ? null : data.getTurnId();
        String author = turnId == null ? CompositionService.AUTHOR_USER : CompositionService.AUTHOR_AI;
        return toBean(service.save(compositionId, itemId, content, data.getBaseRevision(), author, turnId), true);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteCompositionItem(long compositionId, long itemId) {
        service.removeItem(compositionId, itemId);
    }

    /** {@inheritDoc} */
    @Override
    public List<CompositionRevision> listCompositionItemRevisions(long compositionId, long itemId) {
        return service.revisions(compositionId, itemId).stream().map(r -> {
            CompositionRevision bean = new CompositionRevision();
            bean.setId(r.id);
            bean.setContentRevision(r.contentRevision);
            bean.setAuthor(CompositionRevision.Author.fromValue(r.author));
            bean.setTurnId(r.turnId);
            bean.setCreatedAt(Date.from(r.createdOn));
            return bean;
        }).toList();
    }

    /** {@inheritDoc} */
    @Override
    public CompositionItem restoreCompositionItemRevision(long compositionId, long itemId, long revisionId) {
        return toBean(service.restore(compositionId, itemId, revisionId), true);
    }

    /** {@inheritDoc} */
    @Override
    public AssistantValidateResult validateCompositionItem(long compositionId, long itemId,
            CompositionItemValidateRequest data) {
        CompositionItemEntity item = service.item(compositionId, itemId);
        if (data.getContent() == null) {
            throw new IllegalArgumentException("content is required");
        }
        JsonNode content = mapper.valueToTree(data.getContent().getAdditionalProperties());
        List<io.apitomy.axiom.app.validation.ConfigProblem> problems = validator.validateContent(compositionId,
                ConfigItemType.fromName(item.itemType), item.name, content);
        AssistantValidateResult result = new AssistantValidateResult();
        result.setValid(isValid(problems));
        result.setProblems(toProblemBeans(problems));
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public CompositionValidationResult validateComposition(long compositionId) {
        List<CompositionItemProblems> items = validator.validateAll(compositionId).entrySet().stream()
                .map(entry -> {
                    CompositionItemEntity item = service.item(compositionId, entry.getKey());
                    CompositionItemProblems bean = new CompositionItemProblems();
                    bean.setItemId(item.id);
                    bean.setType(CompositionItemProblems.Type.fromValue(item.itemType));
                    bean.setName(item.name);
                    bean.setValid(isValid(entry.getValue()));
                    bean.setProblems(toProblemBeans(entry.getValue()));
                    return bean;
                }).toList();
        CompositionValidationResult result = new CompositionValidationResult();
        result.setItems(items);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public CompositionPublishResult publishComposition(long compositionId, CompositionPublishRequest data) {
        CompositionPublisher.Outcome outcome = publisher.publish(compositionId, data.getItemIds(),
                Boolean.TRUE.equals(data.getForce()));
        CompositionPublishResult result = new CompositionPublishResult();
        result.setCreated(outcome.created());
        result.setUpdated(outcome.updated());
        result.setItems(outcome.items().stream().map(i -> toBean(i, false)).toList());
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Composition sendCompositionMessage(long compositionId, CompositionMessage data) {
        if (data.getMessage() == null || data.getMessage().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        List<String> nodeIds = data.getNodeIds() == null ? List.of() : data.getNodeIds();
        List<String> edgeIds = data.getEdgeIds() == null ? List.of() : data.getEdgeIds();
        turns.start(compositionId, data.getMessage(), nodeIds, edgeIds);
        return toBean(service.get(compositionId));
    }

    /** {@inheritDoc} */
    @Override
    public Composition ensureCompositionSession(long compositionId) {
        service.get(compositionId);
        workbenchSessions.ensure(compositionId);
        // ensure() commits in its own transaction; the entity loaded above is stale in this context.
        return QuarkusTransaction.requiringNew().call(() -> toBean(service.get(compositionId)));
    }

    /** {@inheritDoc} */
    @Override
    public void interruptComposition(long compositionId) {
        service.get(compositionId);
        turns.interrupt(compositionId);
    }

    /** {@inheritDoc} */
    @Override
    public void resolveCompositionChangeSet(long compositionId, CompositionChangeSetResolution data) {
        if (data.getTurnId() == null || data.getOutcome() == null) {
            throw new IllegalArgumentException("turnId and outcome are required");
        }
        turns.resolve(compositionId, data.getTurnId(), data.getOutcome().value(),
                Boolean.TRUE.equals(data.getFromPending()));
    }

    /** {@inheritDoc} */
    @Override
    public CompositionUndoResult undoCompositionTurn(long compositionId, String turnId) {
        TurnUndo.Result undone = turnUndo.undo(compositionId, turnId);
        CompositionUndoResult result = new CompositionUndoResult();
        result.setRestored(undone.restored());
        result.setRemoved(undone.removed());
        result.setSkipped(undone.skipped());
        result.setWorkflowBefore(toContentBean(undone.workflowBefore()));
        result.setWorkflowRestorable(undone.workflowRestorable());
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public AgentComposition getAgentComposition(long compositionId) {
        requireToken(compositionId);
        CompositionEntity composition = service.get(compositionId);
        JsonNode workflow = turns.currentWorkflow(compositionId);
        Map<Long, List<io.apitomy.axiom.app.validation.ConfigProblem>> problems = validator.validateAll(compositionId);
        AgentComposition result = new AgentComposition();
        result.setCompositionId(compositionId);
        result.setTurnId(turns.currentTurnId(compositionId));
        result.setGoal(composition.goal);
        List<AgentItemSummary> items = service.items(compositionId).stream().map(item -> {
            if (ConfigItemType.WORKFLOW.jsonName().equals(item.itemType)) {
                result.setWorkflowName(item.name);
            }
            List<io.apitomy.axiom.app.validation.ConfigProblem> itemProblems =
                    problems.getOrDefault(item.id, List.of());
            AgentItemSummary summary = new AgentItemSummary();
            summary.setType(item.itemType);
            summary.setName(item.name);
            summary.setContentRevision(item.contentRevision);
            summary.setValid(isValid(itemProblems));
            summary.setProblems(toProblemBeans(itemProblems));
            return summary;
        }).toList();
        result.setItems(items);
        if (workflow != null) {
            result.setWorkflow(toContentBean(workflow));
            result.setWorkflowRevision(ContentRevision.of(workflow));
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public CompositionContent getAgentItem(long compositionId, String itemType, String itemName) {
        requireToken(compositionId);
        CompositionItemEntity item = service.items(compositionId).stream()
                .filter(i -> i.itemType.equals(itemType) && i.name.equals(itemName))
                .findFirst()
                .orElseThrow(() -> new CompositionNotFoundException(
                        "No " + itemType + " named " + itemName + " in composition " + compositionId));
        return toContentBean(service.contentOf(item));
    }

    /** {@inheritDoc} */
    @Override
    public AgentItemResult upsertAgentItem(long compositionId, AgentItemUpsert data) {
        requireToken(compositionId);
        if (data.getType() == null || data.getContent() == null) {
            throw new IllegalArgumentException("type and content are required");
        }
        CompositionTurns.AgentWrite write = turns.upsert(compositionId, data.getType().value(),
                toJson(data.getContent()));
        AgentItemResult result = new AgentItemResult();
        result.setType(write.item().itemType);
        result.setName(write.item().name);
        result.setContentRevision(write.item().contentRevision);
        result.setCreated(write.created());
        result.setValid(isValid(write.problems()));
        result.setProblems(toProblemBeans(write.problems()));
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public void removeAgentItem(long compositionId, String itemType, String itemName) {
        requireToken(compositionId);
        turns.remove(compositionId, itemType, itemName);
    }

    /** {@inheritDoc} */
    @Override
    public AgentChangeSetResult proposeAgentWorkflowChanges(long compositionId, CompositionContent data) {
        requireToken(compositionId);
        CompositionTurns.ChangeSetOutcome outcome = turns.applyChangeSet(compositionId, toJson(data));
        AgentChangeSetResult result = new AgentChangeSetResult();
        result.setApplied(outcome.applied());
        result.setContentRevision(outcome.contentRevision());
        result.setProblems(toProblemBeans(outcome.problems()));
        if (outcome.error() != null) {
            ChangeSetError error = new ChangeSetError();
            error.setCode(outcome.error().code());
            error.setOpIndex(outcome.error().opIndex());
            error.setReason(outcome.error().reason());
            result.setError(error);
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public CompositionTestRun testCompositionItem(long compositionId, long itemId, CompositionTestRequest data) {
        CompositionItemEntity item = service.item(compositionId, itemId);
        if (ConfigItemType.WORKFLOW.jsonName().equals(item.itemType)) {
            throw new IllegalArgumentException("The workflow cannot be test-run");
        }
        return testRuns.run(compositionId, item, testValues(data.getInputs()), data.getSubjectId(),
                CompositionService.AUTHOR_USER, null);
    }

    /** {@inheritDoc} */
    @Override
    public List<CompositionTestRun> listCompositionTestRuns(long compositionId) {
        service.get(compositionId);
        return testRuns.list(compositionId);
    }

    /** {@inheritDoc} */
    @Override
    public CompositionTestRun suggestAgentTest(long compositionId, AgentTestSuggestion data) {
        requireToken(compositionId);
        String type = data.getType().value();
        CompositionItemEntity item = service.items(compositionId).stream()
                .filter(i -> type.equals(i.itemType) && data.getName().equals(i.name))
                .findFirst()
                .orElseThrow(() -> new CompositionNotFoundException(
                        "No " + type + " named " + data.getName() + " in composition " + compositionId));
        return testRuns.suggest(compositionId, item, testValues(data.getInputs()), turns.currentTurnId(compositionId));
    }

    private static Map<String, Object> testValues(CompositionTestValues values) {
        return values == null ? Map.of() : values.getAdditionalProperties();
    }

    private void requireToken(long compositionId) {
        tokenGuard.require(workbenchSessions.live(compositionId), httpHeaders.getHeaderString(SessionTokenGuard.HEADER));
    }

    private JsonNode toJson(CompositionContent content) {
        return mapper.valueToTree(content.getAdditionalProperties());
    }

    @SuppressWarnings("unchecked")
    private CompositionContent toContentBean(JsonNode json) {
        if (json == null) {
            return null;
        }
        CompositionContent content = new CompositionContent();
        mapper.convertValue(json, Map.class).forEach((k, v) -> content.setAdditionalProperty((String) k, v));
        return content;
    }

    private static boolean isValid(List<io.apitomy.axiom.app.validation.ConfigProblem> problems) {
        return problems.stream().noneMatch(io.apitomy.axiom.app.validation.ConfigProblem::isError);
    }

    private static List<ConfigProblem> toProblemBeans(List<io.apitomy.axiom.app.validation.ConfigProblem> problems) {
        return problems.stream().map(p -> {
            ConfigProblem bean = new ConfigProblem();
            bean.setSeverity(ConfigProblem.Severity.fromValue(p.severity()));
            bean.setPath(p.path());
            bean.setCode(p.code());
            bean.setMessage(p.message());
            return bean;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private Composition toBean(CompositionEntity entity) {
        Composition bean = new Composition();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setGoal(entity.goal);
        bean.setEngine(entity.engine);
        bean.setModel(entity.model);
        bean.setAssistantSessionId(entity.assistantSessionId);
        AssistantSession session = entity.assistantSessionId != null
                ? sessionManager.getSession(entity.assistantSessionId) : null;
        if (session != null) {
            CompositionSessionInfo info = new CompositionSessionInfo();
            info.setEngine(session.getEngineType());
            info.setEngineLabel(session.getEngineLabel());
            info.setModel(session.getModel());
            bean.setSession(info);
        }
        bean.setTurnState(Composition.TurnState.fromValue(entity.turnState));
        bean.setApplyMode(Composition.ApplyMode.fromValue(entity.applyMode));
        bean.setCreatedAt(Date.from(entity.createdOn));
        bean.setUpdatedAt(Date.from(entity.updatedOn));
        bean.setCurrentTurnId(entity.currentTurnId);
        bean.setPendingTurnId(entity.pendingTurnId);
        if (entity.pendingChangeSet != null) {
            try {
                CompositionContent changeSet = new CompositionContent();
                mapper.convertValue(mapper.readTree(entity.pendingChangeSet), Map.class)
                        .forEach((k, v) -> changeSet.setAdditionalProperty((String) k, v));
                bean.setPendingChangeSet(changeSet);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Invalid pending change set JSON", e);
            }
        }
        return bean;
    }

    @SuppressWarnings("unchecked")
    private CompositionItem toBean(CompositionItemEntity entity, boolean withContent) {
        CompositionItem bean = new CompositionItem();
        bean.setId(entity.id);
        bean.setType(CompositionItem.Type.fromValue(entity.itemType));
        bean.setName(entity.name);
        bean.setContentRevision(entity.contentRevision);
        bean.setBaseItemId(entity.baseItemId);
        bean.setLastAuthor(CompositionItem.LastAuthor.fromValue(entity.lastAuthor));
        bean.setLastTurnId(entity.lastTurnId);
        bean.setPublishedRevision(entity.publishedRevision);
        bean.setPublishedAt(entity.publishedOn == null ? null : Date.from(entity.publishedOn));
        bean.setChanged(CompositionService.isChanged(entity));
        if (withContent) {
            CompositionContent content = new CompositionContent();
            mapper.convertValue(service.contentOf(entity), Map.class)
                    .forEach((k, v) -> content.setAdditionalProperty((String) k, v));
            bean.setContent(content);
        }
        return bean;
    }

    private static Response error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    /**
     * @param e the exception
     * @return 404
     */
    @ServerExceptionMapper
    public Response mapNotFound(CompositionNotFoundException e) {
        return error(404, e.getMessage());
    }

    /**
     * @param e the exception
     * @return 409
     */
    @ServerExceptionMapper
    public Response mapConflict(CompositionConflictException e) {
        return error(409, e.getMessage());
    }

    /**
     * @param e the exception
     * @return 409 with the current revision
     */
    @ServerExceptionMapper
    public Response mapStale(StaleRevisionException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        body.put("currentRevision", e.currentRevision());
        return Response.status(409).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    /**
     * @param e the exception
     * @return 400
     */
    @ServerExceptionMapper
    public Response mapBadRequest(IllegalArgumentException e) {
        return error(400, e.getMessage());
    }

    /**
     * @param e the exception
     * @return 422 with problems keyed by "&lt;type&gt;/&lt;name&gt;"
     */
    @ServerExceptionMapper
    public Response mapPublishBlocked(PublishBlockedException e) {
        Map<String, Object> problems = new LinkedHashMap<>();
        e.problems().forEach((key, list) -> problems.put(key, toProblemBeans(list)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        body.put("problems", problems);
        return Response.status(422).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    /**
     * @param e the exception
     * @return 429 when too many test runs are in progress
     */
    @ServerExceptionMapper
    public Response mapTooManyTestRuns(TooManyTestRunsException e) {
        return error(429, e.getMessage());
    }

    /**
     * @param e the exception
     * @return 503 when the workbench agent could not be started or reached
     */
    @ServerExceptionMapper
    public Response mapUnavailable(AgentUnavailableException e) {
        return error(503, e.getMessage());
    }
}
