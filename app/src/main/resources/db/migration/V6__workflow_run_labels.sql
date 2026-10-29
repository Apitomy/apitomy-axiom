-- Axiom 3.x incremental migration 6.
--
-- Workflow run labels become a list of strings, stored like subject labels (#526). Existing run labels (a JSON
-- key/value map) are dropped, not migrated.
create table workflow_run_label (
    workflow_run_id bigint not null,
    label varchar(255) not null
);

alter table if exists workflow_run_label
    add constraint fk_workflow_run_label_run
    foreign key (workflow_run_id)
    references workflow_run on delete cascade;

CREATE INDEX idx_workflow_run_label ON workflow_run_label (label, workflow_run_id);

alter table workflow_run_label add unique (workflow_run_id, label);

ALTER TABLE workflow_run DROP COLUMN labels;
