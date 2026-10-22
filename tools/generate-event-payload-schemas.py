#!/usr/bin/env python3
"""One-time generator for core/src/main/resources/schemas/events/payloads/*.json.

The generated files are hand-maintained afterwards. Re-running overwrites them, so only use it to bootstrap.
"""
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "core/src/main/resources/schemas/events/payloads"


def ref(name, desc):
    return {"anyOf": [{"$ref": f"#/$defs/{name}"}, {"type": "null"}], "description": desc}


def s(desc, examples=None):
    d = {"type": ["string", "null"], "description": desc}
    if examples:
        d["examples"] = examples
    return d


ISSUE = {"$ref": "#/$defs/NormalizedIssue", "description": "The issue, as of this event."}
PR = {"$ref": "#/$defs/NormalizedPullRequest", "description": "The pull request, as of this event."}

TYPES = {
    "issue.created": ("An issue was created.", {"issue": ISSUE}, ["issue"]),
    "issue.updated": ("An issue field changed.", {"issue": ISSUE, "change": ref("Change", "The field change.")}, ["issue"]),
    "issue.closed": ("An issue was closed.", {"issue": ISSUE}, ["issue"]),
    "issue.reopened": ("A closed issue was reopened.", {"issue": ISSUE}, ["issue"]),
    "issue.assigned": ("A user was assigned to an issue.", {"issue": ISSUE, "assignee": ref("Actor", "The assigned user.")}, ["issue"]),
    "issue.unassigned": ("A user was unassigned from an issue.", {"issue": ISSUE, "assignee": ref("Actor", "The unassigned user.")}, ["issue"]),
    "issue.labeled": ("A label was added to an issue.", {"issue": ISSUE, "label": ref("NormalizedLabel", "The added label.")}, ["issue"]),
    "issue.unlabeled": ("A label was removed from an issue.", {"issue": ISSUE, "label": ref("NormalizedLabel", "The removed label.")}, ["issue"]),
    "issue.comment.created": ("A comment was added to an issue.", {"issue": ISSUE, "comment": ref("NormalizedComment", "The comment.")}, ["issue"]),
    "issue.comment.updated": ("An issue comment was edited.", {"issue": ISSUE, "comment": ref("NormalizedComment", "The comment after the edit.")}, ["issue"]),
    "issue.comment.deleted": ("An issue comment was deleted.", {"issue": ISSUE, "comment": ref("NormalizedComment", "The deleted comment.")}, ["issue"]),
    "pr.created": ("A pull request was opened.", {"pullRequest": PR}, ["pullRequest"]),
    "pr.closed": ("A pull request was closed without merging.", {"pullRequest": PR}, ["pullRequest"]),
    "pr.merged": ("A pull request was merged.", {"pullRequest": PR}, ["pullRequest"]),
    "pr.reopened": ("A pull request was reopened.", {"pullRequest": PR}, ["pullRequest"]),
    "pr.review_requested": ("A review was requested on a pull request.", {"pullRequest": PR, "requestedReviewer": ref("Actor", "The requested reviewer.")}, ["pullRequest"]),
    "pr.review.submitted": ("A pull request review was submitted.", {"pullRequest": PR, "review": ref("NormalizedReview", "The review.")}, ["pullRequest"]),
    "pr.comment.created": ("A comment was added to a pull request.", {"pullRequest": PR, "comment": ref("NormalizedComment", "The comment.")}, ["pullRequest"]),
    "pr.labeled": ("A label was added to a pull request.", {"pullRequest": PR, "label": ref("NormalizedLabel", "The added label.")}, ["pullRequest"]),
    "pr.unlabeled": ("A label was removed from a pull request.", {"pullRequest": PR, "label": ref("NormalizedLabel", "The removed label.")}, ["pullRequest"]),
    "pr.synchronize": ("New commits were pushed to a pull request.", {"pullRequest": PR, "before": s("Head SHA before the push."), "after": s("Head SHA after the push.")}, ["pullRequest"]),
    "push": ("Commits were pushed to a branch.", {
        "ref": s("Full Git ref.", ["refs/heads/main"]), "branch": s("Branch name.", ["main"]),
        "beforeSha": s("SHA before the push."), "afterSha": s("SHA after the push."),
        "forced": {"type": ["boolean", "null"], "description": "Force push."},
        "commits": {"type": ["array", "null"], "items": {"$ref": "#/$defs/Commit"}, "description": "Pushed commits."}}, []),
    "branch.created": ("A branch was created.", {"ref": s("Branch name.", ["feature/x"]), "defaultBranch": s("Repository default branch.", ["main"])}, []),
    "branch.deleted": ("A branch was deleted.", {"ref": s("Branch name.", ["feature/x"])}, []),
    "tag.created": ("A tag was created.", {"ref": s("Tag name.", ["v1.2.0"]), "defaultBranch": s("Repository default branch.", ["main"])}, []),
    "release.published": ("A release was published.", {
        "tagName": s("Release tag.", ["v1.2.0"]), "name": s("Release name."), "body": s("Release notes."),
        "isDraft": {"type": ["boolean", "null"], "description": "Draft release."},
        "isPrerelease": {"type": ["boolean", "null"], "description": "Pre-release."},
        "author": ref("Actor", "Release author."), "url": s("Release URL."),
        "createdAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Creation time."},
        "publishedAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Publication time."}}, []),
}

for event_type, (description, props, required) in TYPES.items():
    schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "description": description,
        "type": "object",
        "additionalProperties": False,
        "required": ["type"] + required,
        "properties": {"type": {"const": event_type, "description": "Payload type (same as the event type)."}, **props},
    }
    (OUT / f"{event_type}.json").write_text(json.dumps(schema, indent=2) + "\n")
print(f"wrote {len(TYPES)} files to {OUT}")
