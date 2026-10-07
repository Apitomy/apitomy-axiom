package io.apitomy.axiom.app;

import io.apitomy.axiom.core.config.AxiomHome;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards that a running test application keeps sessions, workspaces and task files under the isolated test
 * home, never under the developer's real {@code ~/.axiom}.
 */
@QuarkusTest
class AxiomHomeIsolationTest {

    @Inject
    WorkspaceService workspaceService;

    @Test
    void applicationUsesTheIsolatedHome() {
        Path home = AxiomHome.path();
        assertTrue(home.toString().contains("axiom-test-home"), home.toString());

        SubjectEntity subject = new SubjectEntity();
        subject.id = 424242L;
        Path workspace = workspaceService.getWorkspacePath(subject);
        assertTrue(workspace.startsWith(home), "workspace " + workspace + " must be under " + home);
    }
}
