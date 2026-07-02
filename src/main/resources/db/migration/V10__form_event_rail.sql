-- Flyway V10 — forms→workflow inbound event rail.
--
-- Adds the two tables that let a form's lifecycle START (and resume) workflows:
--   • luke_form_event_outbox            — transactional outbox: a form state change (submitted,
--                                         processed, …) is written here in the SAME transaction,
--                                         then drained + correlated to Camunda (FormEventConsumer).
--   • luke_workflow_trigger_subscription — the start-on-event registry: what a published workflow
--                                         listens for (capability + event + optional formCode →
--                                         deployed process key). One row per definition, replaced
--                                         at publish. Avoids Camunda's global message-start-name
--                                         uniqueness constraint (plain none-start launched by key).
--
-- Conventions mirror V9__workflow_tables.sql: varchar(255) strings, timestamp(6) for LocalDateTime,
-- plain int → integer, status/enum as plain varchar, snake_case columns, indexes declared separately.
-- Under the postgres profile Hibernate ddl-auto is `none`, so THIS migration is the source of truth;
-- it must stay faithful to FormEventOutbox / WorkflowTriggerSubscription.


    create table if not exists luke_form_event_outbox (
        id varchar(255) not null,
        created_at timestamp(6) not null,
        error_message text,
        event_type varchar(255) not null,
        form_code varchar(255),
        instance_id varchar(255),
        payload_json text,
        retry_count integer not null,
        state varchar(255) not null,
        tenant_id varchar(255) not null,
        updated_at timestamp(6),
        primary key (id)
    );

    create table if not exists luke_workflow_trigger_subscription (
        id varchar(255) not null,
        capability varchar(255) not null,
        created_at timestamp(6) not null,
        definition_id varchar(255) not null,
        event_type varchar(255) not null,
        form_code varchar(255),
        process_id varchar(255) not null,
        tenant_id varchar(255) not null,
        version integer not null,
        primary key (id)
    );

    -- ── indexes ──────────────────────────────────────────────────────────────────

    create index if not exists idx_formevent_state
       on luke_form_event_outbox (state);

    create index if not exists idx_wfsub_match
       on luke_workflow_trigger_subscription (tenant_id, capability, event_type);

    create index if not exists idx_wfsub_def
       on luke_workflow_trigger_subscription (definition_id);
