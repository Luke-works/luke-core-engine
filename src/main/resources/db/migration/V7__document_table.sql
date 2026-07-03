-- Flyway V7 — DOCUMENTS layer: the luke_document system-of-record table (DOC-2).
--
-- Bytes live in S3 (behind luke-file-proxy); THIS row is the durable, queryable, tenant-isolated
-- index + authZ source + status machine that maps a stable docId to its storage key. Under the
-- postgres profile Hibernate ddl-auto is `none`, so this migration is the schema source of truth
-- and must stay faithful to com.luke.engine.document.Document.
--
-- Conventions mirror V6__signature_tables.sql: varchar(255) default strings, timestamp(6) for
-- LocalDateTime, @Version Long -> bigint, status/kind as plain varchar(N) (no CHECK), snake_case
-- columns, indexes declared separately, IF NOT EXISTS for harmless re-runs.

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
