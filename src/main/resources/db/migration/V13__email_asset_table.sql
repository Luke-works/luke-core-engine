-- Flyway V13 — EMAIL ASSETS: the luke_email_asset system-of-record table.
--
-- A DEDICATED, public store for images uploaded in the email builder (logos etc.), separate from
-- luke_document: email images are inherently public (they load in a recipient's mail client via a bare
-- <img src> with no auth), so they are served by a public, id-as-bearer route and never behind the
-- tenant/capability gate that private documents require. Bytes live in S3 (behind luke-file-proxy);
-- THIS row maps a stable assetId to its storage key, scoped to tenant + template.
--
-- Under the postgres profile Hibernate ddl-auto is `none`, so this migration is the schema source of
-- truth and must stay faithful to com.luke.engine.emailasset.EmailAsset. Conventions mirror
-- V7__document_table.sql: varchar(255) default strings, timestamp(6) for LocalDateTime, @Version Long
-- -> bigint, status as plain varchar (no CHECK), snake_case columns, indexes declared separately.

    create table if not exists luke_email_asset (
        id varchar(255) not null,
        version bigint,
        tenant_id varchar(255) not null,
        template_id varchar(255),
        -- storage + integrity (storage_key is server-only, never returned to clients)
        storage_key varchar(1024) not null,
        filename varchar(512) not null,
        content_type varchar(255) not null,
        size_bytes bigint,
        sha256 varchar(64),
        -- lifecycle: PENDING -> READY (only READY is served)
        status varchar(32) not null,
        created_by varchar(255),
        created_by_name varchar(512),
        created_at timestamp(6) not null,
        primary key (id)
    );

    create index if not exists idx_email_asset_tenant_template on luke_email_asset (tenant_id, template_id);
    create index if not exists idx_email_asset_tenant on luke_email_asset (tenant_id);
