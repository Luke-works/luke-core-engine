-- Flyway baseline (V1) for the custom luke_* application tables (#24).
--
-- Generated from the JPA metadata by SchemaBaselineGeneratorTest using the same
-- naming strategies Spring Boot applies at runtime + the Postgres dialect. To
-- regenerate after entity changes:
--   ./mvnw -Dtest=SchemaBaselineGeneratorTest test
--   diff target/generated-luke-schema.sql against this file (then add IF NOT EXISTS).
--
-- Camunda's ACT_* tables are intentionally NOT here: the engine manages its own
-- schema via camunda.bpm.database.schema-update. Flyway owns ONLY these tables.
--
-- IF NOT EXISTS keeps this idempotent: on existing dev/qa/prod schemas Flyway
-- baselines (baseline-on-migrate) and never runs V1; on a fresh schema it creates
-- the tables. Either way re-running is harmless.


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
