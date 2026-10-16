package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.SubjectEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a workspace removes only what is inside it: symbolic links are removed as links, never followed
 * (issue #340).
 */
class WorkspaceServiceTest {

    @TempDir
    Path tmp;

    private WorkspaceService service(Path root) {
        WorkspaceService service = new WorkspaceService();
        service.workspaceRoot = root.toString();
        return service;
    }

    private static SubjectEntity subject() {
        SubjectEntity subject = new SubjectEntity();
        subject.id = 42L;
        return subject;
    }

    @Test
    void deleteDoesNotFollowSymlinksOutOfTheWorkspace() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "keep me");
        Path outsideFile = Files.writeString(tmp.resolve("outside-file.txt"), "keep me too");

        WorkspaceService service = service(tmp.resolve("workspaces"));
        Path workspace = service.ensureWorkspace(subject());
        Files.writeString(workspace.resolve("own.txt"), "mine");
        Path nested = Files.createDirectories(workspace.resolve("repo"));
        Files.createSymbolicLink(nested.resolve("linked-dir"), outside);
        Files.createSymbolicLink(workspace.resolve("linked-file"), outsideFile);

        service.deleteWorkspace(subject());

        assertFalse(Files.exists(workspace), "the workspace is deleted");
        assertTrue(Files.exists(precious), "files behind a directory symlink survive");
        assertTrue(Files.exists(outsideFile), "the target of a file symlink survives");
    }

    @Test
    void deleteRemovesABrokenSymlink() throws IOException {
        WorkspaceService service = service(tmp.resolve("workspaces"));
        Path workspace = service.ensureWorkspace(subject());
        Files.createSymbolicLink(workspace.resolve("dangling"), tmp.resolve("does-not-exist"));

        service.deleteWorkspace(subject());

        assertFalse(Files.exists(workspace, LinkOption.NOFOLLOW_LINKS), "the workspace is deleted");
    }
}
