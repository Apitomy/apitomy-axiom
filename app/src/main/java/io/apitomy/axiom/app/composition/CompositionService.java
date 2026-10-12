package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.events.SseEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Creates and edits workbench compositions. Every content change goes through {@link #save}, which checks the
 * caller's base revision, records a revision when the content (not just the layout) changed, and announces the
 * change over SSE after the transaction commits.
 */
@ApplicationScoped
public class CompositionService {

    /** Author of edits made in the UI. */
    public static final String AUTHOR_USER = "user";
    /** Author of edits made by the workbench agent. */
    public static final String AUTHOR_AI = "ai";

    @Inject
    ObjectMapper mapper;

    @Inject
    ImportExportService importExportService;

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    TransactionSynchronizationRegistry transactions;

    /**
     * Creates a composition, optionally seeded with an existing workflow, the action types its action nodes use
     * and the Axiom tools those action types allow.
     *
     * @param name the composition name (required)
     * @param goal the user's goal, or null
     * @param engine the engine override, or null
     * @param model the model override, or null
     * @param seedWorkflowId an existing workflow definition id, or null
     * @return the composition
     * @throws IllegalArgumentException if the name is blank or the seed workflow does not exist
     */
    @Transactional
    public CompositionEntity create(String name, String goal, String engine, String model, Long seedWorkflowId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required");
        }
        CompositionEntity composition = new CompositionEntity();
        composition.name = name.trim();
        composition.goal = goal;
        composition.engine = engine;
        composition.model = model;
        composition.createdOn = Instant.now();
        composition.updatedOn = composition.createdOn;
        composition.persist();
        if (seedWorkflowId != null) {
            seed(composition.id, seedWorkflowId);
        }
        return composition;
    }

    private void seed(long compositionId, long workflowId) {
        if (WorkflowDefinitionEntity.findById(workflowId) == null) {
            throw new IllegalArgumentException("Workflow definition not found: " + workflowId);
        }
        CompositionItemEntity workflow = addExisting(compositionId, ConfigItemType.WORKFLOW, workflowId);
        for (String actionTypeName : CompositionItems.referencedActionTypes(contentOf(workflow))) {
            ActionTypeEntity actionType = ActionTypeEntity.find("name", actionTypeName).firstResult();
            if (actionType == null || exists(compositionId, ConfigItemType.ACTION_TYPE, actionTypeName)) {
                continue;
            }
            CompositionItemEntity actionTypeItem = addExisting(compositionId, ConfigItemType.ACTION_TYPE, actionType.id);
            for (String toolName : CompositionItems.referencedTools(contentOf(actionTypeItem))) {
                ToolDefinitionEntity tool = ToolDefinitionEntity.find("name", toolName).firstResult();
                if (tool != null && !exists(compositionId, ConfigItemType.TOOL, toolName)) {
                    addExisting(compositionId, ConfigItemType.TOOL, tool.id);
                }
            }
        }
    }

    /**
     * @return all compositions, newest first
     */
    public List<CompositionEntity> list() {
        return CompositionEntity.list("order by updatedOn desc");
    }

    /**
     * @param id the composition id
     * @return the composition
     * @throws CompositionNotFoundException if it does not exist
     */
    public CompositionEntity get(long id) {
        CompositionEntity composition = CompositionEntity.findById(id);
        if (composition == null) {
            throw new CompositionNotFoundException("Composition not found: " + id);
        }
        return composition;
    }

    /**
     * Renames a composition or changes its apply mode. Null arguments are left unchanged.
     *
     * @param id the composition id
     * @param name the new name, or null
     * @param applyMode {@code live} or {@code review}, or null
     * @return the composition
     */
    @Transactional
    public CompositionEntity update(long id, String name, String applyMode) {
        CompositionEntity composition = get(id);
        if (name != null) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("'name' must not be blank");
            }
            composition.name = name.trim();
        }
        if (applyMode != null) {
            if (!CompositionEntity.APPLY_LIVE.equals(applyMode) && !CompositionEntity.APPLY_REVIEW.equals(applyMode)) {
                throw new IllegalArgumentException("'applyMode' must be live or review");
            }
            composition.applyMode = applyMode;
        }
        composition.updatedOn = Instant.now();
        return composition;
    }

    /**
     * Deletes a composition and all its drafts and revisions. Published items are not affected.
     *
     * @param id the composition id
     */
    @Transactional
    public void delete(long id) {
        CompositionEntity composition = get(id);
        for (CompositionItemEntity item : items(id)) {
            CompositionRevisionEntity.delete("itemId", item.id);
            item.delete();
        }
        composition.delete();
    }

    /**
     * @param compositionId the composition id
     * @return the items: the workflow first, then action types, then tools, each by name
     */
    public List<CompositionItemEntity> items(long compositionId) {
        get(compositionId);
        List<CompositionItemEntity> items = CompositionItemEntity.list("compositionId", compositionId);
        return items.stream()
                .sorted(java.util.Comparator.<CompositionItemEntity>comparingInt(i -> typeOrder(i.itemType))
                        .thenComparing(i -> i.name))
                .toList();
    }

    private static int typeOrder(String itemType) {
        return switch (itemType) {
            case "workflow" -> 0;
            case "action-type" -> 1;
            default -> 2;
        };
    }

    /**
     * @param compositionId the composition id
     * @param itemId the item id
     * @return the item
     * @throws CompositionNotFoundException if the composition or item does not exist
     */
    public CompositionItemEntity item(long compositionId, long itemId) {
        get(compositionId);
        CompositionItemEntity item = CompositionItemEntity.findById(itemId);
        if (item == null || item.compositionId != compositionId) {
            throw new CompositionNotFoundException("Composition item not found: " + itemId);
        }
        return item;
    }

    /**
     * Adds a new, empty draft.
     *
     * @param compositionId the composition id
     * @param type workflow, action type or tool
     * @param name the item name
     * @return the item
     * @throws CompositionConflictException for a second workflow or a duplicate name
     */
    @Transactional
    public CompositionItemEntity addBlank(long compositionId, ConfigItemType type, String name) {
        requireSupported(type);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required");
        }
        String trimmed = name.trim();
        checkCanAdd(compositionId, type, trimmed);
        return insert(compositionId, type, trimmed, CompositionItems.blank(type, trimmed, mapper), null, null);
    }

    /**
     * Adds a draft copy of a published item.
     *
     * @param compositionId the composition id
     * @param type workflow, action type or tool
     * @param existingId the database id of the published item
     * @return the item
     * @throws IllegalArgumentException if the published item does not exist
     */
    @Transactional
    public CompositionItemEntity addExisting(long compositionId, ConfigItemType type, long existingId) {
        requireSupported(type);
        String name = publishedName(type, existingId);
        if (name == null) {
            throw new IllegalArgumentException("No " + type.jsonName() + " with id " + existingId);
        }
        checkCanAdd(compositionId, type, name);
        JsonNode content = publishedContent(type, existingId);
        return insert(compositionId, type, name, content, existingId, CompositionItems.revisionOf(content));
    }

    /**
     * Saves new content for an item. For action types and tools, {@code content.name} renames the item.
     * Layout-only workflow edits update the stored content without a new revision.
     *
     * @param compositionId the composition id
     * @param itemId the item id
     * @param content the complete new content (a JSON object)
     * @param baseRevision the revision the caller edited, or null to skip the check
     * @param author {@link #AUTHOR_USER} or {@link #AUTHOR_AI}
     * @param turnId the AI turn id, or null
     * @return the saved item
     * @throws StaleRevisionException if {@code baseRevision} is not the current revision
     */
    @Transactional
    public CompositionItemEntity save(long compositionId, long itemId, JsonNode content, String baseRevision,
                                      String author, String turnId) {
        item(compositionId, itemId);
        CompositionItemEntity item = CompositionItemEntity.findById(itemId,
                jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (baseRevision != null && !baseRevision.equals(item.contentRevision)) {
            throw new StaleRevisionException(item.contentRevision);
        }
        if (content == null || !content.isObject()) {
            throw new IllegalArgumentException("'content' must be a JSON object");
        }
        ConfigItemType type = ConfigItemType.fromName(item.itemType);
        if (type != ConfigItemType.WORKFLOW) {
            String newName = CompositionItems.nameOf(content);
            if (newName == null) {
                throw new IllegalArgumentException("'content.name' is required");
            }
            if (!newName.equals(item.name)) {
                checkUniqueName(compositionId, type, newName);
                item.name = newName;
            }
        }
        String revision = CompositionItems.revisionOf(content);
        item.content = content.toString();
        item.updatedOn = Instant.now();
        if (!revision.equals(item.contentRevision)) {
            item.contentRevision = revision;
            item.lastAuthor = author;
            item.lastTurnId = turnId;
            addRevision(item, author, turnId);
            fireAfterCommit(SseEvent.compositionItemChanged(compositionId, item.id, revision, author, turnId));
        }
        return item;
    }

    /**
     * @param compositionId the composition id
     * @param itemId the item id
     * @return the item's revisions, newest first
     */
    public List<CompositionRevisionEntity> revisions(long compositionId, long itemId) {
        item(compositionId, itemId);
        return CompositionRevisionEntity.list("itemId = ?1 order by createdOn desc, id desc", itemId);
    }

    /**
     * Restores an earlier revision as a new user revision.
     *
     * @param compositionId the composition id
     * @param itemId the item id
     * @param revisionId the revision to restore
     * @return the item
     */
    @Transactional
    public CompositionItemEntity restore(long compositionId, long itemId, long revisionId) {
        item(compositionId, itemId);
        CompositionRevisionEntity revision = CompositionRevisionEntity.findById(revisionId);
        if (revision == null || !Objects.equals(revision.itemId, itemId)) {
            throw new CompositionNotFoundException("Revision not found: " + revisionId);
        }
        return save(compositionId, itemId, parse(revision.content), null, AUTHOR_USER, null);
    }

    /**
     * Removes a draft from the composition. The published item, if any, is not deleted.
     *
     * @param compositionId the composition id
     * @param itemId the item id
     */
    @Transactional
    public void removeItem(long compositionId, long itemId) {
        CompositionItemEntity item = item(compositionId, itemId);
        CompositionRevisionEntity.delete("itemId", item.id);
        item.delete();
    }

    /**
     * @param item a composition item
     * @return its content as JSON
     */
    public JsonNode contentOf(CompositionItemEntity item) {
        return parse(item.content);
    }

    /**
     * Computes the hash of a published item's JSON as it is now.
     *
     * @param type the item type
     * @param baseItemId the published item's database id
     * @return the revision, or null if the item no longer exists
     */
    public String currentBaseRevision(ConfigItemType type, long baseItemId) {
        if (publishedName(type, baseItemId) == null) {
            return null;
        }
        return CompositionItems.revisionOf(publishedContent(type, baseItemId));
    }

    /**
     * Whether an item has changes that are not published: compared with its last publish, or else with the
     * published item it was loaded from. New items are always changed.
     *
     * @param item the item
     * @return true if publishing it would change something
     */
    public static boolean isChanged(CompositionItemEntity item) {
        if (item.publishedRevision != null) {
            return !item.publishedRevision.equals(item.contentRevision);
        }
        return item.baseContentRevision == null || !item.baseContentRevision.equals(item.contentRevision);
    }

    private CompositionItemEntity insert(long compositionId, ConfigItemType type, String name, JsonNode content,
                                         Long baseItemId, String baseRevision) {
        CompositionItemEntity item = new CompositionItemEntity();
        item.compositionId = compositionId;
        item.itemType = type.jsonName();
        item.name = name;
        item.baseItemId = baseItemId;
        item.baseContentRevision = baseRevision;
        item.content = content.toString();
        item.contentRevision = CompositionItems.revisionOf(content);
        item.lastAuthor = AUTHOR_USER;
        item.createdOn = Instant.now();
        item.updatedOn = item.createdOn;
        item.persist();
        addRevision(item, AUTHOR_USER, null);
        fireAfterCommit(SseEvent.compositionItemChanged(compositionId, item.id, item.contentRevision, AUTHOR_USER, null));
        return item;
    }

    private void addRevision(CompositionItemEntity item, String author, String turnId) {
        CompositionRevisionEntity revision = new CompositionRevisionEntity();
        revision.itemId = item.id;
        revision.content = item.content;
        revision.contentRevision = item.contentRevision;
        revision.author = author;
        revision.turnId = turnId;
        revision.createdOn = Instant.now();
        revision.persist();
    }

    private void checkCanAdd(long compositionId, ConfigItemType type, String name) {
        get(compositionId);
        if (type == ConfigItemType.WORKFLOW
                && CompositionItemEntity.count("compositionId = ?1 and itemType = ?2", compositionId, type.jsonName()) > 0) {
            throw new CompositionConflictException("A composition holds exactly one workflow");
        }
        checkUniqueName(compositionId, type, name);
    }

    private void checkUniqueName(long compositionId, ConfigItemType type, String name) {
        if (exists(compositionId, type, name)) {
            throw new CompositionConflictException("The composition already has a " + type.jsonName() + " named " + name);
        }
    }

    private static boolean exists(long compositionId, ConfigItemType type, String name) {
        return CompositionItemEntity.count("compositionId = ?1 and itemType = ?2 and name = ?3",
                compositionId, type.jsonName(), name) > 0;
    }

    private static void requireSupported(ConfigItemType type) {
        if (!CompositionItems.SUPPORTED.contains(type)) {
            throw new IllegalArgumentException("Item type not supported in compositions: " + type.jsonName());
        }
    }

    private static String publishedName(ConfigItemType type, long id) {
        return switch (type) {
            case WORKFLOW -> {
                WorkflowDefinitionEntity e = WorkflowDefinitionEntity.findById(id);
                yield e == null ? null : e.name;
            }
            case ACTION_TYPE -> {
                ActionTypeEntity e = ActionTypeEntity.findById(id);
                yield e == null ? null : e.name;
            }
            case TOOL -> {
                ToolDefinitionEntity e = ToolDefinitionEntity.findById(id);
                yield e == null ? null : e.name;
            }
            default -> null;
        };
    }

    private JsonNode publishedContent(ConfigItemType type, long id) {
        return switch (type) {
            case WORKFLOW -> {
                WorkflowDefinitionEntity e = WorkflowDefinitionEntity.findById(id);
                yield e.content == null || e.content.isBlank()
                        ? CompositionItems.blank(ConfigItemType.WORKFLOW, e.name, mapper) : parse(e.content);
            }
            case ACTION_TYPE -> importExportService.serializeActionType(ActionTypeEntity.findById(id));
            case TOOL -> importExportService.serializeTool(ToolDefinitionEntity.findById(id));
            default -> throw new IllegalArgumentException("Unsupported type " + type);
        };
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored composition content is not valid JSON", e);
        }
    }

    private void fireAfterCommit(SseEvent event) {
        transactions.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                    sseEvents.fire(event);
                }
            }
        });
    }
}
