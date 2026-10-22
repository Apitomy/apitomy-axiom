#!/usr/bin/env python3
"""Compare raw Jira activity with the events Axiom ingested for a Jira event source.

Usage:
    JIRA_EMAIL=me@example.com JIRA_API_TOKEN=... \\
        python3 tools/jira-groundtruth.py http://localhost:9590 \\
        --jira-url https://myorg.atlassian.net --project PROJ --source jira-test \\
        --since 2026-10-21T14:00:00Z [--issue PROJ-1] [--extra-field "Story Points"]

--since must be the event source's baseline (first-poll) time: Axiom does not ingest earlier activity.
Exit code 0 when no expected event is missing and no stored event failed schema validation, 1 otherwise.
"""
import argparse
import base64
import collections
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

UPDATED_FIELDS = {'summary', 'description', 'priority', 'resolution', 'duedate', 'fix version', 'component'}
DONE_NAMES = {'done', 'closed', 'resolved'}
ISO_SUFFIX = 'Z'


def parse_ts(value):
    """Parse a Jira or ISO timestamp into an aware datetime.

    >>> parse_ts('2026-10-21T14:53:07.835+0000').isoformat()
    '2026-10-21T14:53:07.835000+00:00'
    >>> parse_ts('2026-10-21T14:53:07Z').isoformat()
    '2026-10-21T14:53:07+00:00'
    """
    if not value:
        return None
    if value.endswith('Z'):
        value = value[:-1] + '+0000'
    for fmt in ('%Y-%m-%dT%H:%M:%S.%f%z', '%Y-%m-%dT%H:%M:%S%z'):
        try:
            return datetime.strptime(value, fmt)
        except ValueError:
            pass
    raise ValueError(f'Unparseable timestamp: {value}')


def epoch_millis(value):
    """Epoch milliseconds of a Jira timestamp.

    >>> epoch_millis('2026-10-21T14:53:56.781+0000')
    1792594436781
    """
    dt = parse_ts(value)
    return int(dt.timestamp() * 1000)


def item_event_count(item, extra_fields):
    """Number of events the normalizer emits for one changelog item.

    >>> item_event_count({'field': 'labels', 'fromString': 'a', 'toString': 'a b c'}, set())
    2
    >>> item_event_count({'field': 'Comment', 'fromString': 'gone', 'toString': None}, set())
    1
    >>> item_event_count({'field': 'Story Points', 'toString': '5'}, {'story points'})
    1
    >>> item_event_count({'field': 'Intelligence Requested', 'toString': 'x'}, set())
    0
    """
    field = (item.get('field') or '').lower()
    if field in ('status', 'assignee') or field in UPDATED_FIELDS or field in extra_fields:
        return 1
    if field == 'labels':
        before = set((item.get('fromString') or '').split())
        after = set((item.get('toString') or '').split())
        return len(after - before) + len(before - after)
    if field == 'comment':
        return 1 if item.get('fromString') is not None and item.get('toString') is None else 0
    return 0


def expected_history_ids(key, history, extra_fields):
    """sourceEventIds expected for one changelog history entry.

    >>> h = {'id': '9', 'items': [{'field': 'resolution', 'toString': 'Done'}, {'field': 'status'}]}
    >>> expected_history_ids('P-1', h, set())
    ['P-1-9-0', 'P-1-9-1']
    >>> expected_history_ids('P-1', {'id': '8', 'items': [{'field': 'summary'}]}, set())
    ['P-1-8']
    >>> expected_history_ids('P-1', {'id': '7', 'items': [{'field': 'Rank'}]}, set())
    []
    """
    count = sum(item_event_count(item, extra_fields) for item in history.get('items', []))
    if count == 1:
        return [f'{key}-{history["id"]}']
    return [f'{key}-{history["id"]}-{i}' for i in range(count)]


class Jira:
    """Minimal Jira Cloud REST v3 client with retries."""

    def __init__(self, base_url, email, token):
        self.base = base_url.rstrip('/') + '/rest/api/3'
        self.auth = 'Basic ' + base64.b64encode(f'{email}:{token}'.encode()).decode()

    def request(self, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, headers={
            'Authorization': self.auth, 'Content-Type': 'application/json', 'Accept': 'application/json'})
        for attempt in range(5):
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    return json.load(resp)
            except (ConnectionResetError, TimeoutError, urllib.error.URLError) as e:
                if isinstance(e, urllib.error.HTTPError) and e.code not in (429, 500, 502, 503, 504):
                    raise
                time.sleep(2 + attempt * 3)
        raise RuntimeError(f'Jira request failed repeatedly: {path}')

    def issues(self, project, since, issue_key=None):
        jql = (f'key = "{issue_key}"' if issue_key else f'project = "{project}"')
        jql += f' AND updated >= {int(since.timestamp() * 1000)} ORDER BY updated ASC'
        out, token = [], None
        while True:
            body = {'jql': jql, 'maxResults': 100, 'fields': ['created', 'status', 'project', 'resolutiondate']}
            if token:
                body['nextPageToken'] = token
            page = self.request('/search/jql', body)
            out += page.get('issues', [])
            token = page.get('nextPageToken')
            if page.get('isLast', True) or not token:
                return out

    def paged(self, path, key):
        values, start = [], 0
        while True:
            sep = '&' if '?' in path else '?'
            page = self.request(f'{path}{sep}startAt={start}&maxResults=100')
            batch = page.get(key, [])
            values += batch
            start += len(batch)
            if not batch or start >= page.get('total', 0):
                return values


def axiom_events(axiom_url, source):
    events, page = [], 1
    while True:
        query = urllib.parse.urlencode({'filterEventSourceId': source, 'limit': 500, 'page': page})
        url = f'{axiom_url.rstrip("/")}/api/v1/stream/events?{query}'
        with urllib.request.urlopen(url, timeout=60) as resp:
            items = json.load(resp).get('items', [])
        events += items
        if len(items) < 500:
            return events
        page += 1


def is_iso(value):
    return value is None or (isinstance(value, str) and value.endswith(ISO_SUFFIX) and 'T' in value)


def quality(events):
    """Field-quality checks from the ingestion spike; every counter except 'events'/'type:*' should be 0."""
    q = collections.Counter()
    for e in events:
        payload = e.get('payload') or {}
        issue = payload.get('issue') or {}
        source_data = e.get('sourceData') or {}
        q['events'] += 1
        q[f'type:{e["type"]}'] += 1
        if 'project' not in source_data:
            q['missing_sourceData.project'] += 1
        if 'status' not in source_data:
            q['missing_sourceData.status'] += 1
        if issue.get('state') == 'closed' and not issue.get('closedAt'):
            q['closed_state_without_closedAt'] += 1
        if e['type'] == 'issue.closed' and issue.get('state') != 'closed':
            q['closed_event_but_snapshot_open'] += 1
        if e['type'] == 'issue.reopened' and issue.get('state') != 'open':
            q['reopened_event_but_snapshot_closed'] += 1
        if e['type'] in ('issue.assigned', 'issue.unassigned') and not (payload.get('assignee') or {}).get('login'):
            q['assign_event_assignee_no_login'] += 1
        if e.get('actor') is None:
            q['null_actor'] += 1
        for field in ('createdAt', 'updatedAt', 'closedAt'):
            if not is_iso(issue.get(field)):
                q[f'non_iso_issue.{field}'] += 1
            if not is_iso((payload.get('comment') or {}).get(field)):
                q[f'non_iso_comment.{field}'] += 1
        if not is_iso(e.get('timestamp')):
            q['non_iso_timestamp'] += 1
        if issue and not issue.get('title'):
            q['missing_title'] += 1
    return q


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('axiom_url', help='Axiom base URL, e.g. http://localhost:9590')
    parser.add_argument('--jira-url', required=True)
    parser.add_argument('--project', required=True)
    parser.add_argument('--source', required=True, help='Axiom event source id')
    parser.add_argument('--since', required=True, help='baseline of the event source, ISO-8601')
    parser.add_argument('--issue', help='limit the comparison to one issue key')
    parser.add_argument('--extra-field', action='append', default=[], help='configured extraFields name')
    args = parser.parse_args(argv)

    email, token = os.environ.get('JIRA_EMAIL'), os.environ.get('JIRA_API_TOKEN')
    if not email or not token:
        parser.error('JIRA_EMAIL and JIRA_API_TOKEN must be set')
    since = parse_ts(args.since).astimezone(timezone.utc)
    extra = {name.lower() for name in args.extra_field}
    jira = Jira(args.jira_url, email, token)

    events = axiom_events(args.axiom_url, args.source)
    if args.issue:
        events = [e for e in events if e.get('ref', '').endswith('/browse/' + args.issue)]
    stored = {e['sourceEventId'] for e in events}

    expected, ignored_fields = set(), collections.Counter()
    for issue in jira.issues(args.project, since, args.issue):
        key = issue['key']
        if parse_ts(issue['fields']['created']) > since:
            expected.add(f'{key}-created')
        for history in jira.paged(f'/issue/{key}/changelog', 'values'):
            if parse_ts(history['created']) <= since:
                continue
            expected.update(expected_history_ids(key, history, extra))
            for item in history.get('items', []):
                if item_event_count(item, extra) == 0 and (item.get('field') or '').lower() != 'comment':
                    ignored_fields[item.get('field')] += 1
        for comment in jira.paged(f'/issue/{key}/comment?orderBy=created', 'comments'):
            created, updated = parse_ts(comment['created']), parse_ts(comment['updated'])
            if created > since:
                expected.add(f'{key}-comment-{comment["id"]}')
            if updated > created and updated > since:
                expected.add(f'{key}-comment-{comment["id"]}-updated-{epoch_millis(comment["updated"])}')

    missing = sorted(expected - stored)
    unexpected = sorted(stored - expected)
    invalid = [e for e in events if e.get('validationErrors')]
    messages = collections.Counter(m for e in invalid for m in e['validationErrors'])

    print(f'===== {args.project}{" / " + args.issue if args.issue else ""} (source {args.source})')
    print(f'expected events: {len(expected)}, stored events: {len(stored)}')
    print(f'missing ({len(missing)}):', missing[:50])
    print(f'unexpected ({len(unexpected)}):', unexpected[:50])
    print('ignored changelog fields:', ignored_fields.most_common(25))
    print('quality:', json.dumps(dict(sorted(quality(events).items())), indent=1))
    print(f'schema validation: {len(events) - len(invalid)} valid, {len(invalid)} invalid')
    for message, count in messages.most_common(20):
        print(f'  {count:5d}  {message}')
    return 0 if not missing and not invalid else 1


if __name__ == '__main__':
    sys.exit(main())
