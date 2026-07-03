-- Flyway beforeMigrate CALLBACK — ensure the luke_* baseline tables exist before the versioned
-- migrations (V2+) run. Runs at the start of every migrate, on every environment.
--
-- WHY THIS EXISTS: the schema is always non-empty here because Camunda's ACT_* tables share it, so
-- baseline-on-migrate stamps a baseline on first boot. A schema that has the Camunda tables but never
-- ran the old ddl-auto regime (e.g. a fresh QA) therefore got baselined PAST V1 with no luke_* tables —
-- and Flyway will never re-run V1 on an already-baselined history. This callback recreates the baseline
-- tables idempotently so V2's ALTER (and the app at runtime) find them, with NO manual DB reset.
--
-- This is an idempotent copy of the table-CREATING migrations (every statement is "… if not exists"):
-- a no-op where the tables already exist (dev/prod, fresh baseline@0), self-healing where they don't
-- (a Camunda-only schema baselined past those versions). It mirrors V1 AND V6-V9 (see the POST-V1
-- section below) — an env can be baselined past ANY of them, not just V1 (an early qa was baselined
-- past V6, so signature/document/phone/workflow tables were silently absent and every read 500'd).
--
-- KEEP IN SYNC: when a NEW migration CREATEs luke_* tables (V10+), add its idempotent creates here too
-- so a schema baselined past it still self-heals. Column ALTERs on existing tables are NOT covered here
-- (CREATE ... IF NOT EXISTS won't add a column) — those still rely on the versioned migration running;
-- only whole-table absence self-heals via this callback.
--
-- Camunda's ACT_* tables are intentionally NOT here: the engine manages its own schema via
-- camunda.bpm.database.schema-update.


    create table if not exists luke_capabilities (
        id varchar(255) not null,
        code varchar(255) not null unique,
        created_at timestamp(6) not null,
        description varchar(255),
        icon varchar(255),
        name varchar(255) not null,
        route varchar(255),
        status varchar(255) not null,
        tier varchar(255) not null,
        updated_at timestamp(6),
        primary key (id)
    );

    create table if not exists luke_capability_access_requests (
        id varchar(255) not null,
        capability_code varchar(255) not null,
        decided_at timestamp(6),
        decided_by varchar(255),
        decision_note varchar(255),
        note varchar(255),
        requested_at timestamp(6) not null,
        requested_level varchar(255) not null,
        status varchar(255) not null,
        tenant_id varchar(255) not null,
        updated_at timestamp(6),
        user_id varchar(255) not null,
        primary key (id)
    );

    create table if not exists luke_capability_grants (
        id varchar(255) not null,
        capability_code varchar(255) not null,
        created_at timestamp(6) not null,
        granted_by varchar(255),
        level varchar(255) not null,
        tenant_id varchar(255) not null,
        updated_at timestamp(6),
        user_id varchar(255) not null,
        primary key (id),
        constraint uq_grant_tenant_user_capability unique (tenant_id, user_id, capability_code)
    );

    create table if not exists luke_capability_subscriptions (
        id varchar(255) not null,
        capability_code varchar(255) not null,
        created_at timestamp(6) not null,
        status varchar(255) not null,
        tenant_id varchar(255) not null,
        updated_at timestamp(6),
        primary key (id),
        constraint uq_tenant_capability unique (tenant_id, capability_code)
    );

    create table if not exists luke_email_messages (
        id varchar(255) not null,
        bcc varchar(1000),
        cc varchar(1000),
        context text,
        created_at timestamp(6) not null,
        created_by varchar(255),
        error_code integer,
        error_message varchar(2000),
        from_address varchar(255) not null,
        message_stream varchar(255),
        model text,
        postmark_message_id varchar(255),
        reply_to varchar(255),
        sent_at timestamp(6),
        status varchar(255) not null,
        subject varchar(1000),
        tag varchar(255),
        template_alias varchar(255),
        template_id bigint,
        tenant_id varchar(255) not null,
        to_address varchar(1000) not null,
        primary key (id)
    );

    create table if not exists luke_email_servers (
        id varchar(255) not null,
        company_slug varchar(255) not null,
        created_at timestamp(6) not null,
        default_from varchar(255) not null,
        message_stream varchar(255) not null,
        postmark_server_id bigint not null,
        sender_domain varchar(255) not null,
        server_name varchar(255),
        status varchar(255) not null,
        tenant_id varchar(255) not null unique,
        updated_at timestamp(6),
        verified_at timestamp(6),
        verified_domain varchar(255),
        verified_email varchar(255),
        primary key (id)
    );

    create table if not exists luke_email_template_audit (
        id varchar(255) not null,
        action varchar(255) not null,
        actor varchar(255),
        at timestamp(6) not null,
        detail varchar(255),
        email_template_id varchar(255) not null,
        tenant_id varchar(255) not null,
        primary key (id)
    );

    create table if not exists luke_email_template_versions (
        id varchar(255) not null,
        checked_in_at timestamp(6) not null,
        checked_in_by varchar(255),
        doc text not null,
        email_template_id varchar(255) not null,
        postmark_alias varchar(255),
        postmark_template_id bigint,
        subject varchar(255),
        version integer not null,
        primary key (id),
        constraint uq_emailtplversion_tpl_version unique (email_template_id, version)
    );

    create table if not exists luke_email_templates (
        id varchar(255) not null,
        code varchar(255) not null,
        created_at timestamp(6) not null,
        created_by varchar(255),
        deleted_at timestamp(6),
        description varchar(255),
        draft_doc text,
        name varchar(255) not null,
        postmark_alias varchar(255),
        published_version integer,
        status varchar(255) not null,
        subject varchar(255),
        tenant_id varchar(255) not null,
        updated_at timestamp(6),
        updated_by varchar(255),
        primary key (id),
        constraint uq_emailtpl_tenant_code unique (tenant_id, code)
    );

    create table if not exists luke_email_verifications (
        id varchar(255) not null,
        attempts integer not null,
        code_hash varchar(255) not null,
        code_salt varchar(255) not null,
        created_at timestamp(6) not null,
        domain varchar(255) not null,
        email varchar(255) not null,
        expires_at timestamp(6) not null,
        org_name varchar(255) not null,
        status varchar(255) not null,
        tenant_id varchar(255) not null,
        verified_at timestamp(6),
        primary key (id)
    );

    create table if not exists luke_form_audit (
        id varchar(255) not null,
        action varchar(255) not null,
        actor varchar(255),
        at timestamp(6) not null,
        detail varchar(255),
        form_id varchar(255) not null,
        tenant_id varchar(255) not null,
        primary key (id)
    );

    create table if not exists luke_form_definitions (
        id varchar(255) not null,
        code varchar(255) not null,
        created_at timestamp(6) not null,
        created_by varchar(255),
        deleted_at timestamp(6),
        description varchar(255),
        draft_schema text,
        last_tested_at timestamp(6),
        last_tested_by varchar(255),
        locked_at timestamp(6),
        locked_by varchar(255),
        name varchar(255) not null,
        published_version integer,
        status varchar(255) not null,
        tenant_id varchar(255) not null,
        updated_at timestamp(6),
        updated_by varchar(255),
        version bigint,
        primary key (id),
        constraint uq_form_tenant_code unique (tenant_id, code)
    );

    create table if not exists luke_form_instances (
        id varchar(255) not null,
        context text,
        created_at timestamp(6) not null,
        created_by varchar(255),
        data text,
        definition_code varchar(255) not null,
        expires_at timestamp(6),
        prefill text,
        recipient text,
        state varchar(255) not null,
        submitted_at timestamp(6),
        tenant_id varchar(255) not null,
        token varchar(255) not null unique,
        updated_at timestamp(6),
        version integer not null,
        primary key (id)
    );

    create table if not exists luke_form_submission_outbox (
        id varchar(255) not null,
        business_key varchar(255) not null unique,
        created_at timestamp(6) with time zone not null,
        error_message text,
        form_data_json text,
        form_instance_id varchar(255) not null,
        form_meta_json text,
        process_business_key varchar(255),
        process_instance_id varchar(255),
        published_at timestamp(6) with time zone,
        retry_count integer not null,
        status varchar(255) not null,
        tenant_id varchar(255) not null,
        primary key (id)
    );

    create table if not exists luke_form_versions (
        id varchar(255) not null,
        checked_in_at timestamp(6) not null,
        checked_in_by varchar(255),
        form_id varchar(255) not null,
        schema text not null,
        version integer not null,
        primary key (id),
        constraint uq_formversion_form_version unique (form_id, version)
    );

    create table if not exists luke_registered_topics (
        id varchar(255) not null,
        active boolean not null,
        auto_extend_lock boolean not null,
        auto_retry_on_failure boolean not null,
        created_at timestamp(6) not null,
        created_by varchar(255),
        description varchar(255),
        lock_duration_ms bigint not null,
        max_tasks_per_poll integer not null,
        poll_interval_ms bigint,
        poll_type varchar(255) not null,
        resolution_paths varchar(255) not null,
        retries integer not null,
        retry_backoff varchar(255) not null,
        retry_delay_ms bigint,
        stale_lock_timeout_ms bigint,
        topic_name varchar(255) not null unique,
        updated_at timestamp(6),
        worker_class varchar(255),
        worker_name varchar(255),
        worker_type varchar(255) not null,
        primary key (id)
    );

    create table if not exists luke_secrets (
        id varchar(255) not null,
        ciphertext text not null,
        created_at timestamp(6) not null,
        created_by varchar(255),
        description varchar(255),
        iv varchar(255) not null,
        key_id varchar(255) not null,
        last_four varchar(255),
        managed_by varchar(255) not null,
        name varchar(255) not null,
        tenant_id varchar(255) not null,
        updated_at timestamp(6),
        version integer not null,
        primary key (id),
        constraint uq_secret_tenant_name unique (tenant_id, name)
    );

    create index if not exists idx_access_request_tenant_status
       on luke_capability_access_requests (tenant_id, status);

    create index if not exists idx_access_request_tenant_user
       on luke_capability_access_requests (tenant_id, user_id);

    create index if not exists idx_grant_tenant_user
       on luke_capability_grants (tenant_id, user_id);

    create index if not exists idx_subscription_tenant
       on luke_capability_subscriptions (tenant_id);

    create index if not exists idx_email_tenant
       on luke_email_messages (tenant_id);

    create index if not exists idx_email_status
       on luke_email_messages (status);

    create index if not exists idx_email_postmark
       on luke_email_messages (postmark_message_id);

    create index if not exists idx_emailserver_postmark
       on luke_email_servers (postmark_server_id);

    create index if not exists idx_emailtplaudit_tpl
       on luke_email_template_audit (email_template_id);

    create index if not exists idx_emailtplaudit_tenant
       on luke_email_template_audit (tenant_id);

    create index if not exists idx_emailtplversion_tpl
       on luke_email_template_versions (email_template_id);

    create index if not exists idx_emailtpl_tenant
       on luke_email_templates (tenant_id);

    create index if not exists idx_emailverif_tenant
       on luke_email_verifications (tenant_id);

    create index if not exists idx_emailverif_status
       on luke_email_verifications (status);

    create index if not exists idx_formaudit_form
       on luke_form_audit (form_id);

    create index if not exists idx_formaudit_tenant
       on luke_form_audit (tenant_id);

    create index if not exists idx_form_tenant
       on luke_form_definitions (tenant_id);

    create index if not exists idx_forminstance_tenant
       on luke_form_instances (tenant_id);

    create index if not exists idx_forminstance_token
       on luke_form_instances (token);

    create index if not exists idx_forminstance_def
       on luke_form_instances (definition_code);

    create index if not exists idx_outbox_status
       on luke_form_submission_outbox (status);

    create index if not exists idx_formversion_form
       on luke_form_versions (form_id);

    create index if not exists idx_secret_tenant
       on luke_secrets (tenant_id);

    create index if not exists idx_secret_managed
       on luke_secrets (managed_by);


-- ============================================================================
-- POST-V1 SELF-HEAL (added 2026-07): an env baselined PAST V6 (as an early qa was)
-- never ran V6-V9, and this callback previously only recreated V1 — so signature/
-- document/phone/workflow tables were silently absent and every query 500'd. The
-- statements below are idempotent copies of V6-V9 (all CREATE ... IF NOT EXISTS):
-- a no-op where the tables exist, self-healing where they don't. Keep in sync with
-- V6-V9 exactly as V1 above is kept in sync with V1__baseline_luke_tables.sql.
-- ============================================================================

-- ── from V6__signature_tables.sql ──────────────────────────────────────────────────────────────
    create table if not exists luke_signature_definitions (
        id varchar(255) not null,
        version bigint,
        tenant_id varchar(255) not null,
        code varchar(255) not null,
        name varchar(255) not null,
        description varchar(2000),
        status varchar(32) not null,
        published_version integer,
        draft_schema text,
        document_key varchar(255),
        locked_by varchar(255),
        locked_at timestamp(6),
        last_reviewed_at timestamp(6),
        last_reviewed_by varchar(255),
        created_by varchar(255),
        updated_by varchar(255),
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        deleted_at timestamp(6),
        primary key (id),
        constraint uq_sigdef_tenant_code unique (tenant_id, code)
    );

    create table if not exists luke_signature_versions (
        id varchar(255) not null,
        definition_id varchar(255) not null,
        version integer not null,
        schema text,
        checked_in_by varchar(255),
        checked_in_at timestamp(6) not null,
        signed_off_at timestamp(6),
        signed_off_by varchar(255),
        primary key (id),
        constraint uq_sigver_def_version unique (definition_id, version)
    );

    create table if not exists luke_signature_def_audit (
        id varchar(255) not null,
        definition_id varchar(255) not null,
        tenant_id varchar(255) not null,
        action varchar(255) not null,
        actor varchar(255),
        detail varchar(1024),
        at timestamp(6) not null,
        primary key (id)
    );

    create table if not exists luke_signature_instances (
        id varchar(255) not null,
        version bigint,
        tenant_id varchar(255) not null,
        token varchar(255) not null,
        definition_code varchar(255) not null,
        definition_version integer not null,
        name varchar(255) not null,
        state varchar(32) not null,
        values_json text,
        business_key varchar(255),
        process_instance_id varchar(255),
        process_status varchar(32),
        signed_object_key varchar(255),
        signed_sha256 varchar(255),
        seal_status varchar(16),
        seal_error text,
        created_by varchar(255),
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        completed_at timestamp(6),
        expires_at timestamp(6),
        primary key (id),
        constraint uq_siginst_token unique (token),
        constraint uq_siginst_businesskey unique (business_key)
    );

    create table if not exists luke_signature_recipients (
        id varchar(255) not null,
        instance_id varchar(255) not null,
        tenant_id varchar(255) not null,
        signer_id varchar(255) not null,
        name varchar(255) not null,
        email varchar(255) not null,
        signing_order integer not null,
        verify varchar(32),
        state varchar(32) not null,
        sign_token varchar(255) not null,
        signature_object_key varchar(255),
        signed_at timestamp(6),
        primary key (id),
        constraint uq_sigrcpt_token unique (sign_token)
    );

    create table if not exists luke_signature_process_outbox (
        id varchar(255) not null,
        business_key varchar(255) not null,
        instance_id varchar(255) not null,
        tenant_id varchar(255) not null,
        definition_code varchar(255) not null,
        state varchar(255) not null,
        process_instance_id varchar(255),
        error_message text,
        retry_count integer not null,
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        primary key (id),
        constraint uq_sigoutbox_businesskey unique (business_key)
    );

    create table if not exists luke_signature_requests (
        id varchar(255) not null,
        version bigint,
        tenant_id varchar(255) not null,
        code varchar(255) not null,
        name varchar(255) not null,
        status varchar(255) not null,
        signer_email varchar(255) not null,
        signer_name varchar(255) not null,
        signer_phone varchar(255),
        verification_method varchar(32) not null,
        field_page integer not null,
        field_x double precision not null,
        field_y double precision not null,
        field_w double precision not null,
        field_h double precision not null,
        sign_token varchar(255),
        source_object_key varchar(255),
        signed_object_key varchar(255),
        source_sha256 varchar(255),
        signed_sha256 varchar(255),
        size_bytes bigint,
        retain_until timestamp(6),
        created_by varchar(255),
        created_at timestamp(6) not null,
        sent_at timestamp(6),
        signed_at timestamp(6),
        updated_at timestamp(6),
        primary key (id),
        constraint uq_sigreq_tenant_code unique (tenant_id, code),
        constraint uq_sigreq_sign_token unique (sign_token)
    );

    create table if not exists luke_signature_audit (
        id varchar(255) not null,
        request_id varchar(255) not null,
        tenant_id varchar(255) not null,
        action varchar(255) not null,
        actor varchar(255),
        ip_address varchar(255),
        user_agent varchar(1024),
        geo_country varchar(255),
        geo_city varchar(255),
        ip_risk varchar(32),
        detail varchar(1024),
        at timestamp(6) not null,
        primary key (id)
    );

    -- ── indexes ──────────────────────────────────────────────────────────────────

    create index if not exists idx_sigdef_tenant_status
       on luke_signature_definitions (tenant_id, status);

    create index if not exists idx_sigdef_tenant_updated
       on luke_signature_definitions (tenant_id, updated_at);

    create index if not exists idx_sigver_def
       on luke_signature_versions (definition_id, version);

    create index if not exists idx_sigdefaudit_def
       on luke_signature_def_audit (definition_id, at);

    create index if not exists idx_sigdefaudit_tenant
       on luke_signature_def_audit (tenant_id);

    create index if not exists idx_siginst_tenant_state
       on luke_signature_instances (tenant_id, state);

    create index if not exists idx_siginst_def
       on luke_signature_instances (tenant_id, definition_code);

    create index if not exists idx_sigrcpt_instance
       on luke_signature_recipients (instance_id);

    create index if not exists idx_sigrcpt_token
       on luke_signature_recipients (sign_token);

    create index if not exists idx_sigoutbox_state
       on luke_signature_process_outbox (state);

    create index if not exists idx_sigreq_tenant_status
       on luke_signature_requests (tenant_id, status);

    create index if not exists idx_sigreq_retain
       on luke_signature_requests (retain_until);

    create index if not exists idx_sigaudit_request
       on luke_signature_audit (request_id, at);

    create index if not exists idx_sigaudit_tenant
       on luke_signature_audit (tenant_id);

-- ── from V7__document_table.sql ──────────────────────────────────────────────────────────────
    create table if not exists luke_document (
        id varchar(255) not null,
        version bigint,
        tenant_id varchar(255) not null,
        -- process / task linkage (process-centric case file; taskId null = case-level)
        process_ref varchar(255) not null,
        process_instance_id varchar(255),
        task_id varchar(255),
        -- classification
        kind varchar(32) not null,
        capability varchar(32) not null,
        owner_entity_id varchar(255),
        -- storage + integrity (storage_key is server-only, never returned to clients)
        storage_key varchar(1024) not null,
        filename varchar(512) not null,
        content_type varchar(255) not null,
        size_bytes bigint,
        sha256 varchar(64),
        -- lifecycle
        status varchar(32) not null,
        retain_until timestamp(6),
        -- audit
        created_by varchar(255),
        created_by_name varchar(255),
        created_at timestamp(6) not null,
        deleted_at timestamp(6),
        primary key (id)
    );

    create index if not exists idx_document_tenant_process   on luke_document (tenant_id, process_ref);
    create index if not exists idx_document_tenant_task       on luke_document (tenant_id, task_id);
    create index if not exists idx_document_tenant_cap_owner  on luke_document (tenant_id, capability, owner_entity_id);
    create index if not exists idx_document_proc_instance     on luke_document (process_instance_id);
    create index if not exists idx_document_retain            on luke_document (retain_until);

-- ── from V8__phone_tables.sql ──────────────────────────────────────────────────────────────
    create table if not exists luke_phone_calls (
        id varchar(255) not null,
        version bigint,
        tenant_id varchar(255) not null,
        direction varchar(16) not null,
        status varchar(24) not null,
        vapi_call_id varchar(255),
        phone_number_id varchar(255),
        assistant_id varchar(255),
        customer_number varchar(255),
        ended_reason varchar(255),
        transcript text,
        recording_url varchar(1000),
        summary text,
        cost double precision,
        analysis text,
        metadata text,
        error_message varchar(2000),
        business_key varchar(255),
        process_instance_id varchar(255),
        process_status varchar(16),
        created_by varchar(255),
        created_at timestamp(6) not null,
        started_at timestamp(6),
        ended_at timestamp(6),
        updated_at timestamp(6),
        primary key (id),
        constraint uq_phonecall_vapi unique (vapi_call_id)
    );

    create table if not exists luke_phone_numbers (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        vapi_number_id varchar(255) not null,
        number varchar(255) not null,
        provider varchar(24) not null,
        name varchar(255),
        assistant_id varchar(255),
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        primary key (id),
        constraint uq_phonenumber_vapi unique (vapi_number_id)
    );

    create table if not exists luke_phone_settings (
        id varchar(255) not null,
        has_api_key boolean not null,
        default_assistant_id varchar(255),
        default_phone_number_id varchar(255),
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        primary key (id)
    );

    create table if not exists luke_phone_call_outbox (
        id varchar(255) not null,
        business_key varchar(255) not null,
        call_id varchar(255) not null,
        tenant_id varchar(255) not null,
        direction varchar(16) not null,
        state varchar(255) not null,
        process_instance_id varchar(255),
        error_message text,
        retry_count integer not null,
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        primary key (id),
        constraint uq_phoneoutbox_businesskey unique (business_key)
    );

    -- ── indexes ──────────────────────────────────────────────────────────────────

    create index if not exists idx_phonecall_tenant
       on luke_phone_calls (tenant_id);

    create index if not exists idx_phonecall_tenant_status
       on luke_phone_calls (tenant_id, status);

    create index if not exists idx_phonecall_vapi
       on luke_phone_calls (vapi_call_id);

    create index if not exists idx_phonenumber_tenant
       on luke_phone_numbers (tenant_id);

    create index if not exists idx_phonenumber_vapi
       on luke_phone_numbers (vapi_number_id);

    create index if not exists idx_phoneoutbox_state
       on luke_phone_call_outbox (state);

-- ── from V9__workflow_tables.sql ──────────────────────────────────────────────────────────────
    create table if not exists luke_workflow_definitions (
        id varchar(255) not null,
        lock_version bigint,
        tenant_id varchar(255) not null,
        name varchar(255) not null,
        description varchar(255),
        draft_json text,
        status varchar(255) not null,
        latest_version integer not null,
        published_version integer,
        created_by varchar(255),
        updated_by varchar(255),
        created_at timestamp(6) not null,
        updated_at timestamp(6) not null,
        primary key (id)
    );

    create table if not exists luke_workflow_versions (
        id varchar(255) not null,
        definition_id varchar(255) not null,
        version integer not null,
        json_source text not null,
        bpmn_xml text,
        process_id varchar(255),
        compile_ok boolean not null,
        compile_error text,
        checked_in_by varchar(255),
        checked_in_at timestamp(6) not null,
        signed_off_at timestamp(6),
        signed_off_by varchar(255),
        primary key (id),
        constraint uq_wfversion_def_version unique (definition_id, version)
    );

    create table if not exists luke_integration_connections (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        provider_key varchar(255) not null,
        status varchar(255) not null,
        nango_connection_id varchar(255),
        external_account varchar(255),
        scopes text,
        error_state text,
        created_by varchar(255),
        created_at timestamp(6) not null,
        last_used_at timestamp(6),
        primary key (id)
    );

    create table if not exists luke_integration_webhook_log (
        delivery_id varchar(128) not null,
        type varchar(255),
        signature_ok boolean not null,
        processed_at timestamp(6) not null,
        primary key (delivery_id)
    );

    create table if not exists luke_integration_event_outbox (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        message_name varchar(255) not null,
        correlation_key varchar(255),
        connection_id varchar(255),
        event_type varchar(255),
        payload_json text,
        state varchar(255) not null,
        error_message text,
        retry_count integer not null,
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        primary key (id)
    );

    create table if not exists luke_integration_usage_events (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        connection_id varchar(255),
        type varchar(255) not null,
        quantity integer not null,
        idempotency_key varchar(255) not null,
        occurred_at timestamp(6) not null,
        billed_at timestamp(6),
        primary key (id),
        constraint uq_usage_idempotency unique (idempotency_key)
    );

    -- ── indexes ──────────────────────────────────────────────────────────────────

    create index if not exists idx_wfdef_tenant
       on luke_workflow_definitions (tenant_id);

    create index if not exists idx_wfversion_def
       on luke_workflow_versions (definition_id);

    create index if not exists idx_integration_conn_tenant
       on luke_integration_connections (tenant_id);

    create index if not exists idx_intevent_state
       on luke_integration_event_outbox (state);

    create index if not exists idx_usage_tenant
       on luke_integration_usage_events (tenant_id);
