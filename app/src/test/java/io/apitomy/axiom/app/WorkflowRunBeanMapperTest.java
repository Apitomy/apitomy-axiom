package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.flow.model.ActiveBranch;
import io.apitomy.flow.model.HistoryEntry;
import io.apitomy.flow.model.InstanceStatus;
import io.apitomy.flow.model.WorkflowInstance;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
            SubjectEntity subject = new SubjectEntity();
            subject.name = "Fork Run Subject";
            subject.type = "other";
            subject.ref = "test/fork-run";
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();

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

            return new long[] { subject.id, def.id };
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
        entity.title = "Test run";
        entity.subjectId = ids[0];
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

    private static final String ACTIVE_BRANCHES_CONTENT = """
        {
            "id": "fork-wf-2",
            "name": "Fork WF 2",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {}, "position": {"x": 100, "y": 100}},
                {"id": "a1", "type": "action", "name": "Branch A",
                 "config": {"actionType": "test-action"},
                 "position": {"x": 50, "y": 300}},
                {"id": "a2", "type": "action", "name": "Branch B",
                 "config": {"actionType": "test-action"},
                 "position": {"x": 150, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "a1",
                 "priority": 0, "isDefault": false},
                {"id": "edge2", "source": "s1", "target": "a2",
                 "priority": 1, "isDefault": false}
            ]
        }
        """;

    /**
     * Maps a run with two active branches parked at distinct nodes and a history entry carrying
     * a non-null {@code branchId}. Asserts {@code toBean(...)} resolves both branches' node
     * names via the workflow definition and that the history entry's {@code branchId} survives
     * the mapping.
     */
    @Test
    void toBeanMapsActiveBranchesAndHistoryBranchId() throws Exception {
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = "Active Branches Subject";
            subject.type = "other";
            subject.ref = "test/active-branches";
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "Active Branches WF";
            def.content = ACTIVE_BRANCHES_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();

            WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = ACTIVE_BRANCHES_CONTENT;
            version.createdOn = Instant.now();
            version.persist();

            return new long[] { subject.id, def.id };
        });

        WorkflowInstance forkedInstance = WorkflowInstance.builder()
                .id("instance-2")
                .workflowId("fork-wf-2")
                .currentNodeId(null)
                .status(InstanceStatus.WAITING)
                .activeBranches(java.util.List.of(
                        new ActiveBranch("root.0", "a1"),
                        new ActiveBranch("root.1", "a2")))
                .joinArrivals(java.util.Map.of("j1", java.util.List.of("edge1")))
                .createdOn(Instant.now())
                .updatedOn(Instant.now())
                .build()
                .toBuilder()
                .addHistory(new HistoryEntry("s1", "Start", "edge1", null,
                        Instant.now(), Instant.now(), java.util.Map.of(), "root.0"))
                .build();

        WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.title = "Test run";
        entity.subjectId = ids[0];
        entity.definitionId = ids[1];
        entity.definitionVersion = 1;
        entity.status = "waiting";
        entity.currentNodeId = null;
        entity.instanceState = objectMapper.writeValueAsString(forkedInstance);
        entity.startedOn = Instant.now();

        QuarkusTransaction.requiringNew().run(entity::persist);

        io.apitomy.axiom.api.beans.WorkflowInstance bean = workflowRunBeanMapper.toBean(entity);

        assertEquals(2, bean.getActiveBranches().size());
        java.util.List<io.apitomy.axiom.api.beans.ActiveBranch> sorted =
                bean.getActiveBranches().stream()
                        .sorted(Comparator.comparing(
                                io.apitomy.axiom.api.beans.ActiveBranch::getBranchId))
                        .toList();
        assertEquals("root.0", sorted.get(0).getBranchId());
        assertEquals("a1", sorted.get(0).getNodeId());
        assertEquals("Branch A", sorted.get(0).getNodeName());
        assertEquals("root.1", sorted.get(1).getBranchId());
        assertEquals("a2", sorted.get(1).getNodeId());
        assertEquals("Branch B", sorted.get(1).getNodeName());

        assertEquals(1, bean.getHistory().size());
        assertEquals("root.0", bean.getHistory().get(0).getBranchId());

        assertEquals(java.util.List.of("edge1"),
                bean.getJoinArrivals().getAdditionalProperties().get("j1"));
    }
}
