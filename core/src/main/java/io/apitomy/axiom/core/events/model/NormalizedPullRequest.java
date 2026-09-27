package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A normalized pull request, extending the issue concept with PR-specific fields.
 * GitHub only — Jira does not produce PR events.
 *
 * @param number       PR number as string
 * @param title        PR title
 * @param body         PR description (nullable)
 * @param state        normalized state: "open" or "closed"
 * @param stateDetail  source-specific detail (nullable)
 * @param author       who created the PR
 * @param assignees    current assignees
 * @param labels       label names
 * @param milestone    milestone name (nullable)
 * @param url          HTML URL to the PR
 * @param createdAt    creation timestamp (ISO-8601)
 * @param updatedAt    last update timestamp (ISO-8601)
 * @param closedAt     closure timestamp (nullable, ISO-8601)
 * @param headBranch   source branch name
 * @param baseBranch   target branch name
 * @param headSha      latest commit SHA on head
 * @param isDraft      whether the PR is a draft
 * @param isMerged     whether the PR has been merged
 * @param mergedAt     merge timestamp (nullable, ISO-8601)
 * @param mergedBy     who merged (nullable)
 * @param additions    lines added (nullable)
 * @param deletions    lines deleted (nullable)
 * @param changedFiles number of changed files (nullable)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedPullRequest(
        String number,
        String title,
        String body,
        String state,
        String stateDetail,
        Actor author,
        List<Actor> assignees,
        List<String> labels,
        String milestone,
        String url,
        String createdAt,
        String updatedAt,
        String closedAt,
        String headBranch,
        String baseBranch,
        String headSha,
        Boolean isDraft,
        Boolean isMerged,
        String mergedAt,
        Actor mergedBy,
        Integer additions,
        Integer deletions,
        Integer changedFiles
) {
}
