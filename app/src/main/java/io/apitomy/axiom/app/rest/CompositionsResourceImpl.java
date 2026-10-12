package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.CompositionsResource;
import io.apitomy.axiom.api.beans.AssistantValidateResult;
import io.apitomy.axiom.api.beans.Composition;
import io.apitomy.axiom.api.beans.CompositionContent;
import io.apitomy.axiom.api.beans.CompositionItem;
import io.apitomy.axiom.api.beans.CompositionItemProblems;
import io.apitomy.axiom.api.beans.CompositionItemUpdate;
import io.apitomy.axiom.api.beans.CompositionItemValidateRequest;
import io.apitomy.axiom.api.beans.CompositionPublishRequest;
import io.apitomy.axiom.api.beans.CompositionPublishResult;
import io.apitomy.axiom.api.beans.CompositionRevision;
import io.apitomy.axiom.api.beans.CompositionUpdate;
import io.apitomy.axiom.api.beans.CompositionValidationResult;
import io.apitomy.axiom.api.beans.ConfigProblem;
import io.apitomy.axiom.api.beans.NewComposition;
import io.apitomy.axiom.api.beans.NewCompositionItem;
import io.apitomy.axiom.app.composition.CompositionConflictException;
import io.apitomy.axiom.app.composition.CompositionItems;
import io.apitomy.axiom.app.composition.CompositionNotFoundException;
import io.apitomy.axiom.app.composition.CompositionPublisher;
import io.apitomy.axiom.app.composition.CompositionService;
import io.apitomy.axiom.app.composition.CompositionValidator;
import io.apitomy.axiom.app.composition.PublishBlockedException;
import io.apitomy.axiom.app.composition.StaleRevisionException;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST implementation of workbench compositions: drafts, revisions, validation and publish.
 */
@ApplicationScoped
@RunOnVirtualThread
public class CompositionsResourceImpl implements CompositionsResource {

    @Inject
    CompositionService service;

    @Inject
    ObjectMapper mapper;

    @Inject
    CompositionValidator validator;

    @Inject
    CompositionPublisher publisher;

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
                : service.addBlank(compositionId, type, data.getName());
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
        return toBean(service.save(compositionId, itemId, content, data.getBaseRevision(),
                CompositionService.AUTHOR_USER, null), true);
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

    private Composition toBean(CompositionEntity entity) {
        Composition bean = new Composition();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setGoal(entity.goal);
        bean.setEngine(entity.engine);
        bean.setModel(entity.model);
        bean.setAssistantSessionId(entity.assistantSessionId);
        bean.setTurnState(Composition.TurnState.fromValue(entity.turnState));
        bean.setApplyMode(Composition.ApplyMode.fromValue(entity.applyMode));
        bean.setCreatedAt(Date.from(entity.createdOn));
        bean.setUpdatedAt(Date.from(entity.updatedOn));
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
}
