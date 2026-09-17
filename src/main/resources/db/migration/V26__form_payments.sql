-- Form payments (Stripe Connect, bring-your-own-account, direct charges). Four tables:
--   luke_payment_account        — a tenant's connected Stripe account (id IS the tenantId). Holds the
--                                 account id and what we last read about it; never a tenant credential.
--   luke_payment_connect_state  — single-use, short-lived OAuth CSRF state bound to tenant + owner.
--   luke_form_payment           — the charge a submission owes, priced SERVER-side; one per instance.
--   luke_payment_webhook_event  — applied Connect webhook event ids (Stripe delivers at-least-once).
-- Postgres profile only (ddl-auto=none there); H2/tests build these from the entities via ddl-auto.
-- Must stay faithful to the entities — PostgresSchemaValidationTest runs Hibernate validate against a
-- real Postgres + these migrations. Mirrored idempotently in beforeMigrate.sql.
create table if not exists luke_payment_account (
    id varchar(255) not null,
    stripe_account_id varchar(255) not null,
    status varchar(255) not null,
    livemode boolean not null,
    charges_enabled boolean not null,
    details_submitted boolean not null,
    display_name varchar(255),
    default_currency varchar(8),
    country varchar(8),
    connected_by varchar(255),
    connected_at timestamp(6),
    disconnected_at timestamp(6),
    last_synced_at timestamp(6),
    created_at timestamp(6) not null,
    updated_at timestamp(6),
    primary key (id)
);

create index if not exists idx_payment_account_stripe on luke_payment_account (stripe_account_id);

create table if not exists luke_payment_connect_state (
    id varchar(64) not null,
    tenant_id varchar(255) not null,
    user_id varchar(255) not null,
    created_at timestamp(6) not null,
    expires_at timestamp(6) not null,
    primary key (id)
);

create table if not exists luke_form_payment (
    id varchar(255) not null,
    tenant_id varchar(255) not null,
    instance_id varchar(255) not null unique,
    form_code varchar(255) not null,
    form_version integer not null,
    door varchar(32) not null,
    field_key varchar(255) not null,
    stripe_account_id varchar(255) not null,
    livemode boolean not null,
    intent_id varchar(255) unique,
    amount_minor bigint not null,
    currency varchar(3) not null,
    quantity integer,
    mode varchar(16) not null,
    description varchar(1000),
    status varchar(32) not null,
    last_error_code varchar(64),
    last_error_message varchar(500),
    amount_refunded bigint not null,
    reconcile_attempts integer not null default 0,
    disputed boolean not null default false,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    paid_at timestamp(6),
    canceled_at timestamp(6),
    version bigint not null,
    primary key (id)
);

create index if not exists idx_form_payment_tenant on luke_form_payment (tenant_id);
create index if not exists idx_form_payment_status on luke_form_payment (status, updated_at);

create table if not exists luke_payment_webhook_event (
    id varchar(255) not null,
    type varchar(255) not null,
    account_id varchar(255),
    received_at timestamp(6) not null,
    primary key (id)
);
