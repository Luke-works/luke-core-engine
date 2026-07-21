-- Flyway V14 — EMAIL capability: per-address email BOXES (inbound + outbound).
--
-- Adds registered send-from / receive-at addresses on top of the per-tenant Postmark
-- server (luke_email_servers). Under the postgres profile Hibernate ddl-auto is `none`,
-- so THIS migration is the source of truth; it must stay faithful to the JPA entities in
-- src/main/java/com/luke/engine/capability/email/ (EmailBox, EmailServer, EmailMessage).
--
-- Conventions mirror V1/V8/V9: varchar(255) default strings, timestamp(6) for LocalDateTime,
-- @Version Long → bigint, boolean primitives, snake_case, indexes declared separately,
-- IF NOT EXISTS for harmless re-runs.
--
--   • luke_email_boxes            — one row per registered address; direction INBOUND|OUTBOUND.
--       OUTBOUND → its own Postmark message stream (postmark_stream_id).
--       INBOUND  → routed by the public webhook; may fire a workflow (workflow_trigger).
--   • luke_email_servers.inbound_hook_token — per-tenant unguessable token in the inbound
--       webhook URL (/api/public/email/inbound/{token}); resolves the token → tenant.
--   • luke_email_messages.direction — INBOUND|OUTBOUND on stored messages (default OUTBOUND).

    create table if not exists luke_email_boxes (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        direction varchar(255) not null,
        address varchar(255) not null,
        local_part varchar(255),
        display_name varchar(255),
        status varchar(255) not null default 'ACTIVE',
        postmark_stream_id varchar(255),
        routing_key varchar(255),
        workflow_trigger boolean not null default true,
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        primary key (id)
    );

    create unique index if not exists idx_emailbox_tenant_dir_addr
        on luke_email_boxes (tenant_id, direction, address);
    create index if not exists idx_emailbox_tenant on luke_email_boxes (tenant_id);
    create index if not exists idx_emailbox_routing on luke_email_boxes (tenant_id, routing_key);

    alter table luke_email_servers
        add column if not exists inbound_hook_token varchar(255);
    create unique index if not exists idx_emailserver_inbound_token
        on luke_email_servers (inbound_hook_token);

    alter table luke_email_messages
        add column if not exists direction varchar(255) not null default 'OUTBOUND';
