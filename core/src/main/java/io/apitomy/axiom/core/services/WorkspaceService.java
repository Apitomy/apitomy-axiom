package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.SubjectEntity;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Manages work directories for subjects. Each subject gets an independent
 * directory where agents can perform their work (clone repos, generate
 * files, etc.).
 */
@ApplicationScoped
public class WorkspaceService {

    private static final Logger LOG = Logger.getLogger(WorkspaceService.class);

    @ConfigProperty(name = "axiom.workspace.root", defaultValue = "${axiom.home:${user.home}/.axiom}/workspaces")
    String workspaceRoot;

    /**
     * Returns the workspace path for a subject.
     *
     * @param subject the subject
     * @return the workspace directory path
     */
    public Path getWorkspacePath(SubjectEntity subject) {
        return Path.of(workspaceRoot, "subject-" + subject.id);
    }

    /**
     * Ensures a work directory exists for the given subject. Creates
     * the directory if it doesn't exist. Does not clone any repository —
     * users can configure clone tools for action types that need a checkout.
     *
     * @param subject the subject to create a workspace for
     * @return the workspace directory path
     */
    public Path ensureWorkspace(SubjectEntity subject) {
        Path workspace = getWorkspacePath(subject);

        if (Files.exists(workspace)) {
            LOG.debugf("Workspace already exists for subject %d at %s", subject.id, workspace);
            return workspace;
        }

        LOG.infof("Creating workspace for subject %d at %s", subject.id, workspace);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to create workspace directory for subject %d", subject.id);
            throw new WorkspaceException("Failed to create workspace directory: " + e.getMessage(), e);
        }

        return workspace;
    }

    /**
     * Deletes the workspace directory for a subject.
     *
     * @param subject the subject whose workspace should be deleted
     */
    public void deleteWorkspace(SubjectEntity subject) {
        Path workspace = getWorkspacePath(subject);
        if (Files.exists(workspace)) {
            LOG.infof("Deleting workspace for subject %d at %s", subject.id, workspace);
            try {
                deleteRecursively(workspace);
            } catch (IOException e) {
                LOG.warnf(e, "Failed to delete workspace for subject %d", subject.id);
            }
        }
    }

    /**
     * Computes the total disk usage of a subject's workspace directory in bytes.
     *
     * @param subject the subject whose workspace size to compute
     * @return the total size in bytes, or 0 if the workspace doesn't exist
     */
    public long computeDiskUsage(SubjectEntity subject) {
        Path workspace = getWorkspacePath(subject);
        if (!Files.exists(workspace)) {
            return 0;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("du", "-s", "--block-size=1",
                    workspace.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            boolean completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (completed && process.exitValue() == 0 && !output.isEmpty()) {
                return Long.parseLong(output.split("\\s+")[0]);
            }
        } catch (Exception e) {
            LOG.debugf("du command failed for subject %d, falling back to file walk", subject.id);
        }

        try (var stream = Files.walk(workspace)) {
            return stream
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException e) {
            LOG.warnf(e, "Failed to compute disk usage for subject %d", subject.id);
            return 0;
        }
    }

    /**
     * Deletes a file or directory tree. Symbolic links are deleted as links and never followed, so a link inside a
     * workspace cannot cause files outside it to be deleted (issue #340).
     */
    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * Exception thrown when workspace operations fail.
     */
    public static class WorkspaceException extends RuntimeException {

        /**
         * Creates a new workspace exception.
         *
         * @param message the error message
         * @param cause the underlying cause
         */
        public WorkspaceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
