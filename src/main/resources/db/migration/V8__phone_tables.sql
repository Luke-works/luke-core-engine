-- Flyway V8 — PHONE capability tables (Vapi.ai voice integration).
--
-- Creates the luke_phone_* tables for the inbound + outbound phone capability backed
-- by Vapi (https://vapi.ai). Under the postgres profile Hibernate ddl-auto is `none`,
-- so THIS migration is the source of truth for the schema; it must stay faithful to the
-- JPA entities in src/main/java/com/luke/engine/capability/phone/.
--
-- Tables:
--   • luke_phone_calls         — one audit row per inbound/outbound call + its Camunda binding.
--   • luke_phone_numbers       — Vapi-provided or imported (BYO Twilio/Telnyx) numbers a tenant owns.
--   • luke_phone_settings      — per-tenant defaults (one row per tenant; Vapi key lives in the secret store).
--   • luke_phone_call_outbox   — transactional outbox driving the PhoneCallProcess (mirrors signatures).
--
-- Conventions mirror V1__baseline_luke_tables.sql / V6__signature_tables.sql: varchar(255)
-- default strings, timestamp(6) for LocalDateTime, @Version Long → bigint, plain int → integer,
-- status/enum as plain varchar(N) (no CHECK), boolean for primitive booleans, double precision for
-- Double, snake_case columns, indexes declared separately. IF NOT EXISTS keeps re-runs harmless.


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
