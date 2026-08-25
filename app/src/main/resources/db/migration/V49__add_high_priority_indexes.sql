-- =============================================================================
-- V49: Add high-priority indexes for queue polling, event processing, cleanup
-- jobs, and listing pages.  Addresses full-table-scan hotspots and the
-- activity_log deadlock described in #237.
-- See: https://github.com/Apitomy/apitomy-axiom/issues/238
-- =============================================================================

-- activity_log: bulk DELETE during project cleanup
CREATE INDEX idx_activity_log_project ON activity_log (project_id);

-- activity_log: bulk UPDATE (SET event_id = NULL) during EventCleanup
CREATE INDEX idx_activity_log_event ON activity_log (event_id);

-- task: most-queried combination — pending/active counts, queue polling, listing
CREATE INDEX idx_task_project_status ON task (project_id, status);

-- task: TaskQueuePoller SELECT DISTINCT projectId WHERE status = 'Pending'
CREATE INDEX idx_task_status ON task (status);

-- event: exact-match lookup on every inbound event to find/create a project
CREATE INDEX idx_event_issue_ref ON event (issue_ref);

-- event: EventCleanup deletes old events by receivedAt < cutoff
CREATE INDEX idx_event_received_at ON event (received_at);

-- event_queue: PipelineOrchestrator polls for status = 'pending' every cycle
CREATE INDEX idx_event_queue_status ON event_queue (status);

-- report: ReportQueueConsumer polls for status = 'Pending' every cycle
CREATE INDEX idx_report_status ON report (status);

-- report: cascade delete when a report definition is removed; listing filters
CREATE INDEX idx_report_definition ON report (definition_id);

-- event_source_log: count + paginated listing per event source
CREATE INDEX idx_event_source_log_source ON event_source_log (event_source_id);
