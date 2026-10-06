-- Apitomy Axiom baseline schema.
-- The schema was reset on 2026-10-05 (workflow-first streamlining); no upgrade path from earlier versions.

    create sequence action_type_SEQ start with 1 increment by 50;

    create sequence activity_log_SEQ start with 1 increment by 50;

    create sequence agent_lease_SEQ start with 1 increment by 50;

    create sequence agent_SEQ start with 1 increment by 50;

    create sequence ai_usage_SEQ start with 1 increment by 50;

    create sequence connection_poll_log_SEQ start with 1 increment by 50;

    create sequence dashboard_SEQ start with 1 increment by 50;

    create sequence event_processing_ledger_SEQ start with 1 increment by 50;

    create sequence event_subscription_SEQ start with 1 increment by 50;

    create sequence manager_config_SEQ start with 1 increment by 50;

    create sequence mcp_server_SEQ start with 1 increment by 50;

    create sequence project_SEQ start with 1 increment by 50;

    create sequence report_definition_SEQ start with 1 increment by 50;

    create sequence report_definition_version_SEQ start with 1 increment by 50;

    create sequence report_SEQ start with 1 increment by 50;

    create sequence retention_config_SEQ start with 1 increment by 50;

    create sequence routing_outcome_item_SEQ start with 1 increment by 50;

    create sequence routing_outcome_SEQ start with 1 increment by 50;

    create sequence scheduled_job_run_SEQ start with 1 increment by 50;

    create sequence scheduled_job_SEQ start with 1 increment by 50;

    create sequence scheduled_job_version_SEQ start with 1 increment by 50;

    create sequence secret_SEQ start with 1 increment by 50;

    create sequence session_template_SEQ start with 1 increment by 50;

    create sequence system_config_SEQ start with 1 increment by 50;

    create sequence task_SEQ start with 1 increment by 50;

    create sequence thread_entry_SEQ start with 1 increment by 50;

    create sequence tool_definition_SEQ start with 1 increment by 50;

    create sequence tool_execution_SEQ start with 1 increment by 50;

    create sequence toolset_SEQ start with 1 increment by 50;

    create sequence trace_node_SEQ start with 1 increment by 50;

    create sequence workflow_definition_SEQ start with 1 increment by 50;

    create sequence workflow_definition_version_SEQ start with 1 increment by 50;

    create sequence workflow_event_subscription_SEQ start with 1 increment by 50;

    create sequence workflow_run_resume_SEQ start with 1 increment by 50;

    create sequence workflow_run_SEQ start with 1 increment by 50;

    create sequence workflow_wait_SEQ start with 1 increment by 50;

    create table action_type (
        emits_event boolean not null,
        manager_triggerable boolean not null,
        max_budget_usd float(53),
        max_steps integer,
        timeout_seconds integer,
        user_triggerable boolean not null,
        workflow_enabled boolean not null,
        id bigint not null,
        allowed_tools TEXT,
        description TEXT,
        engine varchar(255),
        environment TEXT,
        execution_mode varchar(255) not null,
        model varchar(255),
        name varchar(255) not null unique,
        prompt_template TEXT,
        script_template TEXT,
        primary key (id)
    );

    create table action_type_input (
        ordinal integer not null check ((ordinal>=0)),
        required boolean not null,
        action_type_id bigint not null,
        description TEXT,
        name varchar(255) not null,
        type varchar(255) not null,
        primary key (ordinal, action_type_id)
    );

    create table action_type_label (
        action_type_id bigint not null,
        label varchar(255)
    );

    create table action_type_output (
        ordinal integer not null check ((ordinal>=0)),
        required boolean not null,
        action_type_id bigint not null,
        description TEXT,
        name varchar(255) not null,
        type varchar(255) not null,
        primary key (ordinal, action_type_id)
    );

    create table activity_log (
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        project_id bigint,
        report_definition_id bigint,
        report_id bigint,
        scheduled_job_run_id bigint,
        task_id bigint,
        event_id uuid,
        trace_id uuid,
        summary varchar(1024) not null,
        details TEXT,
        entry_type varchar(255) not null,
        primary key (id)
    );

    create table agent (
        enabled boolean not null,
        id bigint not null,
        agent_type varchar(255) not null,
        configuration TEXT,
        description TEXT,
        name varchar(255) not null,
        primary key (id)
    );

    create table agent_capability (
        agent_id bigint not null,
        capability varchar(255) not null,
        primary key (agent_id, capability)
    );

    create table agent_lease (
        agent_id bigint not null,
        id bigint not null,
        leased_at timestamp(6) with time zone not null,
        work_id bigint not null,
        work_type varchar(255) not null,
        primary key (id)
    );

    create table ai_usage (
        cost_usd float(53),
        agent_id bigint,
        created_on timestamp(6) with time zone not null,
        duration_ms bigint,
        id bigint not null,
        input_tokens bigint,
        output_tokens bigint,
        project_id bigint,
        report_id bigint,
        scheduled_job_run_id bigint,
        task_id bigint,
        event_id uuid,
        trace_id uuid,
        action_type varchar(255),
        engine varchar(255),
        invocation_type varchar(255) not null,
        model varchar(255),
        primary key (id)
    );

    create table connection_poll_log (
        events_ingested integer,
        created_on timestamp(6) with time zone not null,
        duration_ms bigint,
        id bigint not null,
        status varchar(16) not null,
        connection_id varchar(63) not null,
        detail TEXT,
        message TEXT not null,
        primary key (id)
    );

    create table dashboard (
        is_default boolean,
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        updated_on timestamp(6) with time zone not null,
        description TEXT,
        name varchar(255) not null,
        tabs TEXT,
        primary key (id)
    );

    create table dashboard_label (
        dashboard_id bigint not null,
        label varchar(255)
    );

    create table event_processing_ledger (
        attempt_count integer not null,
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        last_attempt_at timestamp(6) with time zone,
        next_attempt_at timestamp(6) with time zone,
        processed_on timestamp(6) with time zone,
        subscription_id bigint not null,
        event_id uuid not null,
        status varchar(32) not null,
        error_message TEXT,
        primary key (id)
    );

    create table event_source_connection (
        enabled boolean not null,
        poll_interval integer,
        created_on timestamp(6) with time zone not null,
        last_polled_at timestamp(6) with time zone,
        modified_on timestamp(6) with time zone not null,
        source_type varchar(32) not null,
        id varchar(63) not null,
        base_url varchar(1024) not null,
        configuration TEXT not null,
        description TEXT,
        name varchar(255) not null,
        secret_name varchar(255),
        primary key (id)
    );

    create table event_subscription (
        enabled boolean not null,
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        modified_on timestamp(6) with time zone not null,
        process_events_from timestamp(6) with time zone,
        description TEXT,
        filters TEXT,
        name varchar(255) not null,
        routing TEXT,
        primary key (id)
    );

    create table event_subscription_label (
        event_subscription_id bigint not null,
        label varchar(255)
    );

    create table manager_config (
        id bigint not null,
        prompt_template TEXT,
        system_prompt TEXT,
        primary key (id)
    );

    create table mcp_server (
        id bigint not null,
        description TEXT,
        name varchar(255) not null unique,
        server_args TEXT,
        server_command varchar(255),
        server_env TEXT,
        server_url varchar(255),
        primary key (id)
    );

    create table project (
        created_on timestamp(6) with time zone not null,
        disk_usage_bytes bigint,
        id bigint not null,
        updated_on timestamp(6) with time zone not null,
        body TEXT,
        metadata TEXT,
        name varchar(255) not null,
        project_ref varchar(255) not null unique,
        ref_source varchar(255),
        repository varchar(255),
        status varchar(255) not null,
        type varchar(255) not null,
        primary key (id)
    );

    create table project_label (
        project_id bigint not null,
        label varchar(255)
    );

    create table report (
        cost_usd float(53),
        completed_on timestamp(6) with time zone,
        config_version_id bigint,
        created_on timestamp(6) with time zone not null,
        definition_id bigint not null,
        duration_ms bigint,
        id bigint not null,
        time_range_end timestamp(6) with time zone,
        time_range_start timestamp(6) with time zone,
        trace_id uuid,
        triggered_by_trace_id uuid,
        content TEXT,
        execution_log TEXT,
        report_trigger varchar(255),
        status varchar(255) not null,
        title varchar(255),
        triggered_by varchar(255),
        primary key (id)
    );

    create table report_definition (
        enabled boolean not null,
        max_budget_usd float(53),
        max_steps integer,
        timeout_seconds integer,
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        last_run_at timestamp(6) with time zone,
        next_run_at timestamp(6) with time zone,
        updated_on timestamp(6) with time zone not null,
        allowed_tools TEXT,
        description TEXT,
        engine varchar(255),
        environment TEXT,
        model varchar(255),
        name varchar(255) not null,
        prompt_template TEXT not null,
        schedule varchar(255) not null,
        schedule_day_of_week varchar(255),
        schedule_time varchar(255),
        slug varchar(255),
        time_window varchar(255),
        title_template TEXT,
        primary key (id)
    );

    create table report_definition_initial_label (
        report_definition_id bigint not null,
        label varchar(255)
    );

    create table report_definition_version (
        created_on timestamp(6) with time zone not null,
        definition_id bigint not null,
        id bigint not null,
        config_hash varchar(64) not null,
        config_snapshot TEXT not null,
        primary key (id),
        constraint uq_rdv_def_hash unique (definition_id, config_hash)
    );

    create table report_label (
        report_id bigint not null,
        label varchar(255)
    );

    create table retention_config (
        activity_log_retention_days integer not null,
        ai_usage_retention_days integer not null,
        closed_project_retention_days integer not null,
        event_retention_days integer not null,
        report_retention_days integer not null,
        scheduled_job_run_retention_days integer not null,
        trace_retention_days integer not null,
        workflow_run_retention_days integer not null,
        id bigint not null,
        primary key (id)
    );

    create table routing_outcome (
        attempt_number integer,
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        ledger_id bigint not null,
        project_id bigint,
        task_id bigint,
        status varchar(16) not null,
        trace_id uuid,
        routing_type varchar(32) not null,
        error_message TEXT,
        summary TEXT,
        primary key (id)
    );

    create table routing_outcome_item (
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        outcome_id bigint not null,
        project_id bigint,
        task_id bigint,
        trace_node_id bigint,
        workflow_run_id bigint,
        status varchar(16) not null,
        item_type varchar(32) not null,
        error_message TEXT,
        summary TEXT,
        primary key (id)
    );

    create table scheduled_job (
        enabled boolean not null,
        max_budget_usd float(53),
        max_steps integer,
        timeout_seconds integer,
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        last_run_at timestamp(6) with time zone,
        next_run_at timestamp(6) with time zone,
        updated_on timestamp(6) with time zone not null,
        allowed_tools TEXT,
        description TEXT,
        engine varchar(255),
        environment TEXT,
        execution_mode varchar(255) not null,
        model varchar(255),
        name varchar(255) not null unique,
        prompt_template TEXT,
        schedule varchar(255) not null,
        schedule_day_of_week varchar(255),
        schedule_time varchar(255),
        script_template TEXT,
        slug varchar(255),
        primary key (id)
    );

    create table scheduled_job_label (
        scheduled_job_id bigint not null,
        label varchar(255)
    );

    create table scheduled_job_run (
        cost_usd float(53),
        completed_at timestamp(6) with time zone,
        config_version_id bigint,
        created_on timestamp(6) with time zone not null,
        duration_ms bigint,
        id bigint not null,
        job_id bigint not null,
        started_at timestamp(6) with time zone,
        trace_id uuid,
        triggered_by_trace_id uuid,
        error TEXT,
        execution_log TEXT,
        output TEXT,
        run_trigger varchar(255) not null,
        status varchar(255) not null,
        triggered_by varchar(255),
        primary key (id)
    );

    create table scheduled_job_version (
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        job_id bigint not null,
        config_hash varchar(64) not null,
        config_snapshot TEXT not null,
        primary key (id),
        constraint uq_sjv_job_hash unique (job_id, config_hash)
    );

    create table secret (
        id bigint not null,
        description TEXT,
        encrypted_value TEXT not null,
        name varchar(255) not null unique,
        primary key (id)
    );

    create table session_template (
        id bigint not null,
        working_directory varchar(1024),
        description TEXT not null,
        engine varchar(255),
        environment TEXT,
        init_script TEXT,
        init_script_type varchar(255),
        initial_message TEXT,
        model varchar(255),
        name varchar(255) not null,
        system_prompt TEXT not null,
        template_id varchar(255) not null unique,
        welcome_message TEXT,
        primary key (id)
    );

    create table session_template_allowed_tool (
        session_template_id bigint not null,
        tool_pattern varchar(255)
    );

    create table session_template_mcp_server (
        session_template_id bigint not null,
        mcp_server_name varchar(255)
    );

    create table stream_event (
        created_on timestamp(6) with time zone not null,
        timestamp timestamp(6) with time zone not null,
        id uuid not null,
        source varchar(32) not null,
        connection_id varchar(63) not null,
        type varchar(64) not null,
        ref varchar(2048) not null,
        actor TEXT not null,
        payload TEXT not null,
        source_data TEXT,
        source_event_id varchar(255) not null,
        primary key (id)
    );

    create table system_config (
        id bigint not null,
        default_engine varchar(255) not null,
        primary key (id)
    );

    create table task (
        assigned_agent bigint,
        completed_on timestamp(6) with time zone,
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        project_id bigint not null,
        workflow_run_id bigint,
        event_id uuid,
        trace_id uuid,
        action_type varchar(255) not null,
        created_by varchar(255) not null,
        execution_log TEXT,
        human_context TEXT,
        input TEXT,
        node_id varchar(255),
        output TEXT,
        output_schema TEXT,
        session_id varchar(255),
        status varchar(255) not null,
        primary key (id)
    );

    create table thread_entry (
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        project_id bigint not null,
        author_id varchar(255),
        author_type varchar(255) not null,
        content TEXT not null,
        entry_type varchar(255) not null,
        primary key (id)
    );

    create table tool_definition (
        id bigint not null,
        description TEXT,
        name varchar(255) not null unique,
        parameters TEXT,
        script_template TEXT,
        primary key (id)
    );

    create table tool_execution (
        created_on timestamp(6) with time zone not null,
        duration_ms bigint,
        id bigint not null,
        trace_id uuid not null,
        status varchar(255) not null,
        tool_input TEXT,
        tool_name varchar(255) not null,
        tool_output TEXT,
        primary key (id)
    );

    create table tool_label (
        tool_id bigint not null,
        label varchar(255)
    );

    create table toolset (
        id bigint not null,
        description TEXT,
        name varchar(255) not null unique,
        tools TEXT not null,
        primary key (id)
    );

    create table trace (
        completed_on timestamp(6) with time zone,
        project_id bigint,
        report_id bigint,
        started_on timestamp(6) with time zone not null,
        event_id uuid,
        trace_id uuid not null,
        summary varchar(1024) not null,
        status varchar(255) not null,
        trace_type varchar(255) not null,
        primary key (trace_id)
    );

    create table trace_node (
        completed_on timestamp(6) with time zone,
        duration_ms bigint,
        id bigint not null,
        parent_node_id bigint,
        started_on timestamp(6) with time zone not null,
        trace_id uuid not null,
        summary varchar(1024) not null,
        entity_id varchar(255),
        entity_type varchar(255),
        node_type varchar(255) not null,
        status varchar(255) not null,
        primary key (id)
    );

    create table workflow_definition (
        current_version integer,
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        updated_on timestamp(6) with time zone not null,
        content TEXT,
        description TEXT,
        name varchar(255) not null unique,
        primary key (id)
    );

    create table workflow_definition_version (
        version integer not null,
        created_on timestamp(6) with time zone not null,
        definition_id bigint not null,
        id bigint not null,
        content TEXT not null,
        primary key (id),
        unique (definition_id, version)
    );

    create table workflow_event_subscription (
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        project_id bigint not null,
        run_id bigint not null,
        event_type varchar(255) not null,
        node_id varchar(255) not null,
        primary key (id)
    );

    create table workflow_run (
        definition_version integer not null,
        completed_on timestamp(6) with time zone,
        definition_id bigint not null,
        id bigint not null,
        project_id bigint not null,
        started_on timestamp(6) with time zone not null,
        trigger_ledger_id bigint,
        trace_id uuid,
        trigger_event_id uuid,
        status varchar(20) not null,
        current_node_id varchar(255),
        failure_reason TEXT,
        instance_state TEXT not null,
        primary key (id)
    );

    create table workflow_run_resume (
        id bigint not null,
        ledger_id bigint,
        resumed_on timestamp(6) with time zone not null,
        run_id bigint not null,
        trace_node_id bigint,
        event_id uuid not null,
        node_id varchar(255) not null,
        primary key (id)
    );

    create table workflow_wait (
        created_on timestamp(6) with time zone not null,
        id bigint not null,
        resume_at timestamp(6) with time zone not null,
        run_id bigint not null,
        node_id varchar(255) not null,
        primary key (id)
    );

    create index idx_epl_status_next 
       on event_processing_ledger (status, next_attempt_at);

    alter table if exists action_type_input 
       add constraint FKqmrreg4chic3yr3gln41cvh86 
       foreign key (action_type_id) 
       references action_type;

    alter table if exists action_type_label 
       add constraint FK48nf227an9jy5uti43i82ll9e 
       foreign key (action_type_id) 
       references action_type;

    alter table if exists action_type_output 
       add constraint FKnddoibhk1dliddnf4u0a1am1m 
       foreign key (action_type_id) 
       references action_type;

    alter table if exists dashboard_label 
       add constraint FKnlao8on34p68quap260sajq2x 
       foreign key (dashboard_id) 
       references dashboard;

    alter table if exists event_subscription_label 
       add constraint FKr5w2dcekh4ir1adnjcrldr2r7 
       foreign key (event_subscription_id) 
       references event_subscription;

    alter table if exists project_label 
       add constraint FK7ojjun8a14mrbws62ha0tbdou 
       foreign key (project_id) 
       references project;

    alter table if exists report_definition_initial_label 
       add constraint FKrt7xooecttchflv6rpgs9a9u7 
       foreign key (report_definition_id) 
       references report_definition;

    alter table if exists report_label 
       add constraint FKh2wq33qdvra6t4anv9941s147 
       foreign key (report_id) 
       references report;

    alter table if exists scheduled_job_label 
       add constraint FKg320v4be9jgfatfqcivv7t956 
       foreign key (scheduled_job_id) 
       references scheduled_job;

    alter table if exists session_template_allowed_tool 
       add constraint FK3rcvftv3im5ore04s9lwclws2 
       foreign key (session_template_id) 
       references session_template;

    alter table if exists session_template_mcp_server 
       add constraint FKjke6h164shgwdwndfpwoqsho1 
       foreign key (session_template_id) 
       references session_template;

    alter table if exists tool_label 
       add constraint FKbwyc8afriwo5cydrv7c1hojs4 
       foreign key (tool_id) 
       references tool_definition;

-- Indexes not declared in the entity model
CREATE INDEX idx_action_type_label ON action_type_label (label, action_type_id);
CREATE INDEX idx_activity_log_created_on ON activity_log (created_on);
CREATE INDEX idx_activity_log_event ON activity_log (event_id);
CREATE INDEX idx_activity_log_project ON activity_log (project_id);
CREATE INDEX idx_activity_log_report ON activity_log (report_id);
CREATE INDEX idx_activity_log_scheduled_job_run ON activity_log (scheduled_job_run_id);
CREATE INDEX idx_activity_log_trace ON activity_log (trace_id);
CREATE INDEX idx_ai_usage_created_on ON ai_usage (created_on);
CREATE INDEX idx_ai_usage_event ON ai_usage (event_id);
CREATE INDEX idx_ai_usage_report ON ai_usage (report_id);
CREATE INDEX idx_ai_usage_scheduled_job_run ON ai_usage (scheduled_job_run_id);
CREATE INDEX idx_ai_usage_trace ON ai_usage (trace_id);
CREATE INDEX idx_cpl_connection ON connection_poll_log (connection_id);
CREATE INDEX idx_cpl_created ON connection_poll_log (created_on);
CREATE INDEX idx_dashboard_label ON dashboard_label (label, dashboard_id);
CREATE INDEX idx_epl_event ON event_processing_ledger (event_id);
CREATE INDEX idx_epl_status ON event_processing_ledger (status);
CREATE INDEX idx_epl_subscription ON event_processing_ledger (subscription_id);
CREATE INDEX idx_project_label ON project_label (label, project_id);
CREATE INDEX idx_report_config_version ON report (config_version_id);
CREATE INDEX idx_report_created_on ON report (created_on);
CREATE INDEX idx_report_definition ON report (definition_id);
CREATE INDEX idx_report_status ON report (status);
CREATE INDEX idx_report_definition_initial_label ON report_definition_initial_label (label, report_definition_id);
CREATE INDEX idx_report_label ON report_label (label, report_id);
CREATE INDEX idx_ro_ledger ON routing_outcome (ledger_id);
CREATE INDEX idx_ro_trace ON routing_outcome (trace_id);
CREATE INDEX idx_roi_outcome ON routing_outcome_item (outcome_id);
CREATE INDEX idx_roi_trace_node ON routing_outcome_item (trace_node_id);
CREATE INDEX idx_roi_workflow_run ON routing_outcome_item (workflow_run_id);
CREATE INDEX idx_scheduled_job_label ON scheduled_job_label (label, scheduled_job_id);
CREATE INDEX idx_scheduled_job_run_job_id ON scheduled_job_run (job_id);
CREATE INDEX idx_scheduled_job_run_status ON scheduled_job_run (status);
CREATE INDEX idx_sjr_config_version ON scheduled_job_run (config_version_id);
CREATE INDEX idx_sjr_created_on ON scheduled_job_run (created_on);
CREATE INDEX idx_stream_event_connection ON stream_event (connection_id);
CREATE INDEX idx_stream_event_created_on ON stream_event (created_on);
CREATE INDEX idx_stream_event_ref ON stream_event (ref);
CREATE UNIQUE INDEX idx_stream_event_source_event_id ON stream_event (source_event_id);
CREATE INDEX idx_stream_event_timestamp ON stream_event (timestamp);
CREATE INDEX idx_stream_event_type ON stream_event (type);
CREATE INDEX idx_task_event ON task (event_id);
CREATE INDEX idx_task_project_status ON task (project_id, status);
CREATE INDEX idx_task_status ON task (status);
CREATE INDEX idx_task_trace ON task (trace_id);
CREATE INDEX idx_task_workflow_run ON task (workflow_run_id);
CREATE INDEX idx_tool_execution_trace ON tool_execution (trace_id);
CREATE INDEX idx_tool_label ON tool_label (label, tool_id);
CREATE INDEX idx_trace_event ON trace (event_id);
CREATE INDEX idx_trace_project ON trace (project_id);
CREATE INDEX idx_trace_report ON trace (report_id);
CREATE INDEX idx_trace_started_on ON trace (started_on);
CREATE INDEX idx_trace_status ON trace (status);
CREATE INDEX idx_trace_node_entity ON trace_node (entity_type, entity_id);
CREATE INDEX idx_trace_node_parent ON trace_node (parent_node_id);
CREATE INDEX idx_trace_node_trace ON trace_node (trace_id);
CREATE INDEX idx_wf_def_version_def_id ON workflow_definition_version (definition_id);
CREATE INDEX idx_wf_event_sub_event_type ON workflow_event_subscription (event_type);
CREATE INDEX idx_wf_event_sub_run ON workflow_event_subscription (run_id);
CREATE INDEX idx_wf_run_completed_on ON workflow_run (completed_on);
CREATE INDEX idx_wf_run_project_started ON workflow_run (project_id, started_on desc);
CREATE INDEX idx_wf_run_status ON workflow_run (status);
CREATE INDEX idx_wf_run_trace ON workflow_run (trace_id);
CREATE INDEX idx_wf_run_trigger_event ON workflow_run (trigger_event_id);
CREATE INDEX idx_wf_run_resume_event ON workflow_run_resume (event_id);
CREATE INDEX idx_wf_run_resume_run ON workflow_run_resume (run_id);
CREATE INDEX idx_wrr_trace_node ON workflow_run_resume (trace_node_id);
CREATE INDEX idx_workflow_wait_resume_at ON workflow_wait (resume_at);
CREATE INDEX idx_workflow_wait_run ON workflow_wait (run_id);
