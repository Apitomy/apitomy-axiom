package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;

import java.time.Instant;

/**
 * Shared persistence fixtures for Quarkus tests.
 */
public final class TestFixtures {

    private static final String WAIT_CONTENT = """
        {
            "id": "wait-wf", "name": "Wait WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
                {"id": "w1", "type": "wait", "name": "Hold", "config": {"duration": "PT1H"},
                 "position": {"x": 0, "y": 100}},
                {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 200}}
            ],
            "edges": [
                {"id": "x1", "source": "s1", "target": "w1", "priority": 0, "isDefault": true},
                {"id": "x2", "source": "w1", "target": "e1", "priority": 0, "isDefault": true}
            ]
        }
        """;

    private TestFixtures() {
    }

    /**
     * Creates a subject of type "other".
     *
     * @param ref the subject name (also used to build its ref)
     * @return the subject id
     */
    public static long createSubject(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = ref;
            subject.type = "other";
            subject.ref = "test/" + ref;
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();
            return subject.id;
        });
    }

    /**
     * Creates a published workflow definition (version 1) that parks at a one-hour wait node.
     *
     * @param name the definition name
     * @return the definition id
     */
    public static long createWaitDefinition(String name) {
        return createDefinition(name, WAIT_CONTENT);
    }

    /**
     * Creates a workflow definition with the given content, published as version 1.
     *
     * @param name the definition name
     * @param content the workflow JSON
     * @return the definition id
     */
    public static long createDefinition(String name, String content) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = name;
            def.content = content;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = content;
            version.createdOn = Instant.now();
            version.persist();
            return def.id;
        });
    }
}
