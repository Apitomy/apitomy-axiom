package io.apitomy.axiom.core.lifecycle;

import java.util.Map;
import java.util.Set;

/**
 * Enforces valid project status transitions.
 *
 * <pre>
 * Created ──────► InProgress ──────► Idle ──────► Completed
 *                     ▲                │               │
 *                     │                │               │
 *                     └────────────────┘               │
 *                       (new event/task)               │
 *                     ▲                                │
 *                     │                                │
 *                     └────────────────────────────────┘
 *                       (re-opened by user or agent)  
 * </pre>
 */
public final class ProjectLifecycle {

    private static final Map<ProjectStatus, Set<ProjectStatus>> VALID_TRANSITIONS = Map.of(
            ProjectStatus.Created, Set.of(ProjectStatus.InProgress),
            ProjectStatus.InProgress, Set.of(ProjectStatus.Idle, ProjectStatus.Completed),
            ProjectStatus.Idle, Set.of(ProjectStatus.InProgress, ProjectStatus.Completed),
            ProjectStatus.Completed, Set.of(ProjectStatus.InProgress)
    );

    private ProjectLifecycle() {
    }

    /**
     * Checks whether transitioning from one status to another is allowed.
     *
     * @param from the current status
     * @param to the desired status
     * @return true if the transition is valid
     */
    public static boolean isValidTransition(ProjectStatus from, ProjectStatus to) {
        Set<ProjectStatus> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Validates a status transition, throwing if it's not allowed.
     *
     * @param from the current status
     * @param to the desired status
     * @throws InvalidStatusTransitionException if the transition is not valid
     */
    public static void validateTransition(ProjectStatus from, ProjectStatus to) {
        if (!isValidTransition(from, to)) {
            throw new InvalidStatusTransitionException(from, to);
        }
    }
}
