-- Flyway V9 — WORKFLOW capability + INTEGRATIONS (Nango) module tables.
--
-- Creates the luke_workflow_* and luke_integration_* tables for the composing WORKFLOW
-- capability (JSON→BPMN design-time lifecycle) and its Nango-backed integrations module.
-- Under the postgres profile Hibernate ddl-auto is `none`, so THIS migration is the source
-- of truth for the schema; it must stay faithful to the JPA entities in
-- src/main/java/com/luke/engine/workflow/ (+ .../workflow/integrations/).
--
-- Tables:
--   • luke_workflow_definitions     — the versioned, tenant-scoped workflow template (editable draft).
--   • luke_workflow_versions        — immutable checked-in snapshots (json + compiled bpmn + sign-off).
--   • luke_integration_connections  — per-tenant Nango connections (tokens live in Nango; we hold the id).
--   • luke_integration_webhook_log  — idempotency/audit for inbound Nango webhook deliveries.
--   • luke_integration_event_outbox — transactional outbox bridging inbound events → Camunda correlation.
--   • luke_integration_usage_events — metered usage (successful executions) with idempotency key.
--
-- Conventions mirror V1__baseline_luke_tables.sql / V8__phone_tables.sql: varchar(255) default
-- strings, timestamp(6) for LocalDateTime, @Version Long → bigint, plain int → integer, enum/status
-- as plain varchar(255) (no CHECK), boolean for primitive booleans, snake_case columns, indexes
-- declared separately. IF NOT EXISTS keeps re-runs harmless.


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
