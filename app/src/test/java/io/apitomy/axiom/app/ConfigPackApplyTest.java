package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.api.beans.PackExportRequest;
import io.apitomy.axiom.app.validation.ConfigValidationException;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests validated, ordered, transactional application of configuration packs. */
@QuarkusTest
class ConfigPackApplyTest {

    private static final String PACK_TEMPLATE = """
        {
          "actionTypes": [
            {"name": "at-SFX", "executionMode": "script", "scriptTemplate": "echo hi"}
          ],
          "workflowDefinitions": [
            {"name": "wf-SFX", "content": {
              "id": "wf-SFX", "name": "wf-SFX",
              "nodes": [
                {"id": "s", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
                {"id": "a", "type": "action", "name": "Do", "config": {"actionType": "at-SFX"},
                 "position": {"x": 200, "y": 0}},
                {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 400, "y": 0}}
              ],
              "edges": [
                {"id": "e1", "source": "s", "target": "a", "priority": 0, "isDefault": true},
                {"id": "e2", "source": "a", "target": "e", "priority": 0, "isDefault": true}
              ]}}
          ],
          "subscriptions": [
            {"name": "sub-SFX", "filterExpression": "event.type == 'issue.created'",
             "routing": [{"type": "create-workflow", "workflowDefinitionName": "wf-SFX"}]}
          ]
        }
        """;

    @Inject
    ImportExportService importExportService;

    @Inject
    ObjectMapper mapper;

    /** A pack with an action type, a workflow using it, and a subscription naming the workflow applies. */
    @Test
    void packWithCrossReferencesApplies() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        JsonNode pack = mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx));
        importExportService.importOrUpdatePack(pack);
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowDefinitionEntity def = WorkflowDefinitionEntity.find("name", "wf-" + sfx).firstResult();
            assertNotNull(def);
            assertEquals(1, def.currentVersion);
            EventSubscriptionEntity sub = EventSubscriptionEntity.find("name", "sub-" + sfx).firstResult();
            assertTrue(sub.routing.contains("\"workflowDefinitionId\":" + def.id), sub.routing);
            assertFalse(sub.enabled);
        });
    }

    /** Workflow content given as a JSON string (older packs) is accepted. */
    @Test
    void stringWorkflowContentIsAccepted() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        JsonNode pack = mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx));
        ObjectNode wf = (ObjectNode) pack.path("workflowDefinitions").get(0);
        wf.put("content", wf.get("content").toString());
        importExportService.importOrUpdatePack(pack);
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowDefinitionEntity def = WorkflowDefinitionEntity.find("name", "wf-" + sfx).firstResult();
            assertNotNull(def);
            assertEquals(1, def.currentVersion);
        });
    }

    /** A blank workflowDefinitionName next to a workflowDefinitionId is treated as absent. */
    @Test
    void blankWorkflowNameIsIgnoredWhenIdPresent() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        importExportService.importOrUpdatePack(mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx)));
        Long defId = QuarkusTransaction.requiringNew().call(() -> WorkflowDefinitionEntity
                .<WorkflowDefinitionEntity>find("name", "wf-" + sfx).firstResult().id);
        JsonNode pack = mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx)
                .replace("\"workflowDefinitionName\": \"wf-" + sfx + "\"",
                        "\"workflowDefinitionId\": " + defId + ", \"workflowDefinitionName\": \"\""));
        importExportService.importOrUpdatePack(pack);
        QuarkusTransaction.requiringNew().run(() -> {
            EventSubscriptionEntity sub = EventSubscriptionEntity.find("name", "sub-" + sfx).firstResult();
            assertTrue(sub.routing.contains("\"workflowDefinitionId\":" + defId), sub.routing);
            assertFalse(sub.routing.contains("workflowDefinitionName"), sub.routing);
        });
    }

    /** An invalid workflow aborts the whole pack: nothing is written. */
    @Test
    void invalidWorkflowRollsBackEverything() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        JsonNode pack = mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx)
                .replace("\"type\": \"end\"", "\"type\": \"END\""));
        assertThrows(ConfigValidationException.class, () -> importExportService.importOrUpdatePack(pack));
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(ActionTypeEntity.find("name", "at-" + sfx).firstResult());
            assertNull(WorkflowDefinitionEntity.find("name", "wf-" + sfx).firstResult());
        });
    }

    /** A subscription naming a workflow that exists nowhere is rejected. */
    @Test
    void unknownWorkflowNameIsRejected() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        JsonNode pack = mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx)
                .replace("\"workflowDefinitionName\": \"wf-" + sfx + "\"", "\"workflowDefinitionName\": \"nope\""));
        ConfigValidationException ex = assertThrows(ConfigValidationException.class,
                () -> importExportService.importOrUpdatePack(pack));
        assertTrue(ex.problemsByItem().containsKey("subscription/sub-" + sfx), ex.problemsByItem().toString());
    }

    /** The create-only import publishes workflows through the publisher (version 1). */
    @Test
    void importPackPublishesWorkflows() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        importExportService.importPack(mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx)));
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowDefinitionEntity def = WorkflowDefinitionEntity.find("name", "wf-" + sfx).firstResult();
            assertNotNull(def);
            assertEquals(1, def.currentVersion);
            assertEquals(1, WorkflowDefinitionVersionEntity.count("definitionId", def.id));
            EventSubscriptionEntity sub = EventSubscriptionEntity.find("name", "sub-" + sfx).firstResult();
            assertTrue(sub.routing.contains("\"workflowDefinitionId\":" + def.id), sub.routing);
        });
    }

    /** An invalid workflow in the create-only import writes nothing. */
    @Test
    void importPackRejectsInvalidWorkflow() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        JsonNode pack = mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx)
                .replace("\"type\": \"end\"", "\"type\": \"END\""));
        assertThrows(ConfigValidationException.class, () -> importExportService.importPack(pack));
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(ActionTypeEntity.find("name", "at-" + sfx).firstResult());
            assertNull(WorkflowDefinitionEntity.find("name", "wf-" + sfx).firstResult());
        });
    }

    /** Re-applying unchanged workflow content does not publish a new version; changed content does. */
    @Test
    void reapplyPublishesOnlyWhenContentChanges() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        String json = PACK_TEMPLATE.replace("SFX", sfx);
        importExportService.importOrUpdatePack(mapper.readTree(json));
        importExportService.importOrUpdatePack(mapper.readTree(json));
        assertEquals(1, currentVersion("wf-" + sfx));
        importExportService.importOrUpdatePack(mapper.readTree(json.replace("\"name\": \"Do\"",
                "\"name\": \"Do it\"")));
        assertEquals(2, currentVersion("wf-" + sfx));
    }

    /** A node-mode action type carries its codeTemplate through import and export. */
    @Test
    void nodeActionTypeImportsAndExports() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        String code = "export async function run(){ return {}; }";
        ObjectNode pack = mapper.createObjectNode();
        ObjectNode at = pack.putArray("actionTypes").addObject();
        at.put("name", "node-" + sfx);
        at.put("executionMode", "node");
        at.put("codeTemplate", code);
        importExportService.importOrUpdatePack(pack);
        JsonNode exported = QuarkusTransaction.requiringNew().call(() -> {
            ActionTypeEntity entity = ActionTypeEntity.find("name", "node-" + sfx).firstResult();
            assertNotNull(entity);
            assertEquals("node", entity.executionMode);
            assertEquals(code, entity.codeTemplate);
            PackExportRequest request = new PackExportRequest();
            request.setName("export-" + sfx);
            request.setActionTypeIds(List.of(entity.id));
            return importExportService.exportPack(request);
        });
        JsonNode exportedType = exported.path("actionTypes").get(0);
        assertEquals("node", exportedType.path("executionMode").asText());
        assertEquals(code, exportedType.path("codeTemplate").asText());
    }

    private Integer currentVersion(String name) {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowDefinitionEntity
                .<WorkflowDefinitionEntity>find("name", name).firstResult().currentVersion);
    }
}
