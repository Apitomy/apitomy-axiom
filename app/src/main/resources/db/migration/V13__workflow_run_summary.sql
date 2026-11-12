-- Axiom 3.x incremental migration 13.
--
-- AI-generated workflow run summaries (#672): per-workflow opt-in, global engine/model, and a summary row
-- per run that is deleted with the run by the database.
alter table workflow_definition add column summarize_runs boolean default false not null;

alter table system_config add column summary_engine varchar(255);
alter table system_config add column summary_model varchar(255);

create table workflow_run_summary (
    run_id bigint not null,
    status varchar(20) not null,
    headline varchar(200),
    body TEXT,
    error TEXT,
    engine varchar(255),
    model varchar(255),
    trigger_type varchar(10) not null,
    attempts integer not null,
    created_on timestamp(6) with time zone not null,
    completed_on timestamp(6) with time zone,
    primary key (run_id)
);

alter table if exists workflow_run_summary
    add constraint fk_workflow_run_summary_run
    foreign key (run_id)
    references workflow_run on delete cascade;

create index idx_workflow_run_summary_status on workflow_run_summary (status);
