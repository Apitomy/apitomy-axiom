package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.ProjectEntity;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages work directories for projects. Each project gets an independent
 * directory where agents can perform their work (clone repos, generate
 * files, etc.).
 */
@ApplicationScoped
public class WorkspaceService {

    private static final Logger LOG = Logger.getLogger(WorkspaceService.class);

    @ConfigProperty(name = "axiom.workspace.root", defaultValue = "${user.home}/.axiom/workspaces")
    String workspaceRoot;

    /**
     * Returns the workspace path for a project.
     *
     * @param project the project
     * @return the workspace directory path
     */
    public Path getWorkspacePath(ProjectEntity project) {
        return Path.of(workspaceRoot, "project-" + project.id);
    }

    /**
     * Ensures a work directory exists for the given project. Creates
     * the directory if it doesn't exist. Does not clone any repository —
     * users can configure clone tools for action types that need a checkout.
     *
     * @param project the project to create a workspace for
     * @return the workspace directory path
     */
    public Path ensureWorkspace(ProjectEntity project) {
        Path workspace = getWorkspacePath(project);

        if (Files.exists(workspace)) {
            LOG.debugf("Workspace already exists for project %d at %s", project.id, workspace);
            return workspace;
        }

        LOG.infof("Creating workspace for project %d at %s", project.id, workspace);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to create workspace directory for project %d", project.id);
            throw new WorkspaceException("Failed to create workspace directory: " + e.getMessage(), e);
        }

        return workspace;
    }

    /**
     * Deletes the workspace directory for a project.
     *
     * @param project the project whose workspace should be deleted
     */
    public void deleteWorkspace(ProjectEntity project) {
        Path workspace = getWorkspacePath(project);
        if (Files.exists(workspace)) {
            LOG.infof("Deleting workspace for project %d at %s", project.id, workspace);
            try {
                deleteRecursively(workspace);
            } catch (IOException e) {
                LOG.warnf(e, "Failed to delete workspace for project %d", project.id);
            }
        }
    }

    /**
     * Computes the total disk usage of a project's workspace directory in bytes.
     *
     * @param project the project whose workspace size to compute
     * @return the total size in bytes, or 0 if the workspace doesn't exist
     */
    public long computeDiskUsage(ProjectEntity project) {
        Path workspace = getWorkspacePath(project);
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
            LOG.debugf("du command failed for project %d, falling back to file walk", project.id);
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
            LOG.warnf(e, "Failed to compute disk usage for project %d", project.id);
            return 0;
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
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
