-- Flyway V6 — SIGNATURES capability tables (SIG-M merge).
--
-- Creates the luke_signature_* tables for the e-signature capability, merged into
-- luke-core-engine from the standalone luke-signature-engine. Under the postgres
-- profile Hibernate ddl-auto is `none`, so THIS migration is the source of truth for
-- the schema; it must stay faithful to the JPA entities in
--   src/main/java/com/luke/engine/capability/signature/
--
-- Two families of tables live here:
--   • Definition-driven (design-time → campaign → instances): luke_signature_definitions,
--     _versions, _def_audit, _instances, _recipients, _process_outbox.
--   • Single-shot request flow: luke_signature_requests, _signature_audit (luke_signature_audit).
--
-- Conventions mirror V1__baseline_luke_tables.sql: varchar(255) default strings,
-- timestamp(6) for LocalDateTime, @Version Long → bigint, plain @Version-less int → integer,
-- enums/string-status as plain varchar(N) (no CHECK), snake_case columns, indexes declared
-- separately. IF NOT EXISTS keeps re-runs harmless.


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
