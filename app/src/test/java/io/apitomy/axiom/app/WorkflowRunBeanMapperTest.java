package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.flow.model.ActiveBranch;
import io.apitomy.flow.model.InstanceStatus;
import io.apitomy.flow.model.WorkflowInstance;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression test for {@link WorkflowRunBeanMapper#toBean(WorkflowRunEntity)} tolerating a
 * parallel-parked run: {@code currentNodeId == null} with multiple active branches recorded in
 * the persisted {@link WorkflowInstance} JSON. This is the state a run is left in while forked
 * branches are executing concurrently and no single "current node" exists.
 */
@QuarkusTest
class WorkflowRunBeanMapperTest {

    @Inject
    WorkflowRunBeanMapper workflowRunBeanMapper;

    @Inject
    ObjectMapper objectMapper;

    private static final String FORK_CONTENT = """
        {
            "id": "fork-wf",
            "name": "Fork WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {}, "position": {"x": 100, "y": 100}},
                {"id": "f1", "type": "fork", "name": "Fork",
                 "config": {}, "position": {"x": 100, "y": 200}},
                {"id": "a1", "type": "action", "name": "Branch A",
                 "config": {"actionType": "test-action"},
                 "position": {"x": 50, "y": 300}},
                {"id": "a2", "type": "action", "name": "Branch B",
                 "config": {"actionType": "test-action"},
                 "position": {"x": 150, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "f1",
                 "priority": 0, "isDefault": true},
                {"id": "edge2", "source": "f1", "target": "a1",
                 "priority": 0, "isDefault": true},
                {"id": "edge3", "source": "f1", "target": "a2",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    /**
     * Maps a {@link WorkflowRunEntity} whose {@code currentNodeId} column is null and whose
     * instance JSON has two active branches (a fork with both children still running). Asserts
     * {@code toBean(...)} does not throw and that the resulting bean's {@code currentNodeName}
     * stays unset (null), since there is no single current node to name while parallel-parked.
     */
    @Test
    void toBeanToleratesNullCurrentNodeIdWithMultipleActiveBranches() throws Exception {
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "Fork Run Project";
            project.type = "other";
            project.status = "new";
            project.ref = "test/fork-run";
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "Fork Run WF";
            def.content = FORK_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();

            WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = FORK_CONTENT;
            version.createdOn = Instant.now();
            version.persist();

            return new long[] { project.id, def.id };
        });

        WorkflowInstance forkedInstance = WorkflowInstance.builder()
                .id("instance-1")
                .workflowId("fork-wf")
                .currentNodeId(null)
                .status(InstanceStatus.WAITING)
                .activeBranches(java.util.List.of(
                        new ActiveBranch("root.0", "a1"),
                        new ActiveBranch("root.1", "a2")))
                .createdOn(Instant.now())
                .updatedOn(Instant.now())
                .build();

        WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.projectId = ids[0];
        entity.definitionId = ids[1];
        entity.definitionVersion = 1;
        entity.status = "waiting";
        entity.currentNodeId = null;
        entity.instanceState = objectMapper.writeValueAsString(forkedInstance);
        entity.startedOn = Instant.now();

        QuarkusTransaction.requiringNew().run(entity::persist);

        io.apitomy.axiom.api.beans.WorkflowInstance[] beanHolder =
                new io.apitomy.axiom.api.beans.WorkflowInstance[1];
        assertDoesNotThrow(() -> beanHolder[0] = workflowRunBeanMapper.toBean(entity),
                "toBean should tolerate a null currentNodeId with multiple active branches");

        assertNull(beanHolder[0].getCurrentNodeName(),
                "currentNodeName should stay unset when currentNodeId is null");
    }
}
