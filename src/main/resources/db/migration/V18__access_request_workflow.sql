-- Flyway V18 — ACCESS REQUEST WORKFLOW: Camunda-orchestrated approval of capability access.
--
-- Raising an access request now starts AccessRequestApprovalProcess, which routes an approval task
-- to the capability's RESOURCE OWNER GROUP (capowner:<tenant>:<CODE>, falling back to the tenant
-- owner group), provisions the grant on approval, and RETURNS a rejected request to the requester
-- to revise and resubmit or withdraw.
--
-- Two schema changes come with it:
--   1. luke_access_request_outbox — the transactional outbox that guarantees a request reaches an
--      approver. The request row and its outbox row are written in one transaction, so accepting a
--      request never depends on the process engine being up; AccessRequestOutboxConsumer drains it.
--      access_request_id is UNIQUE: however many times the consumer retries, at most one process is
--      started per request.
--   2. luke_capability_access_requests gains process_instance_id (which instance orchestrates the
--      request — null for rows raised before this workflow existed, and while the outbox has not
--      drained yet) and resubmit_count (how many times the requester revised after a rejection).
--
-- No status backfill is needed: the new RETURNED status only ever arises from the process, and the
-- existing PENDING/APPROVED/DENIED/CANCELLED rows keep their meaning. Requests without a
-- process_instance_id stay decidable through the controller's legacy path.
--
-- Under the postgres profile Hibernate ddl-auto is `none`, so this migration is the schema source of
-- truth and MUST stay faithful to com.luke.engine.capability.access.AccessRequestOutbox and
-- .AccessRequest (PostgresSchemaValidationTest boots a real Postgres + these migrations and runs
-- Hibernate `validate` against the entities). Conventions mirror V16__audit_event_table.sql:
-- varchar(255) default strings, timestamp(6) with time zone for Instant, snake_case columns via
-- CamelCaseToUnderscoresNamingStrategy, indexes declared separately.

    create table if not exists luke_access_request_outbox (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        -- one row per request: the unique constraint is what makes retries safe
        access_request_id varchar(255) not null,
        -- Camunda business key ("access-request:<id>"), also the start idempotency key
        business_key varchar(255) not null,
        -- QUEUED -> PUBLISHED | FAILED
        status varchar(255) not null,
        process_instance_id varchar(255),
        error_message text,
        retry_count integer not null,
        created_at timestamp(6) with time zone not null,
        published_at timestamp(6) with time zone,
        primary key (id),
        constraint uk_access_outbox_request unique (access_request_id)
    );

    create index if not exists idx_access_outbox_status on luke_access_request_outbox (status);

    alter table luke_capability_access_requests
        add column if not exists process_instance_id varchar(255);

    -- NOT NULL with a default so existing rows land on 0 rather than null (the entity is a
    -- primitive int and Hibernate `validate` expects the column to be non-nullable).
    alter table luke_capability_access_requests
        add column if not exists resubmit_count integer default 0 not null;
