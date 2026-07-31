-- Flyway V23 — EMAIL INTAKE: store what actually arrived, and let a tenant route it.
--
-- V14 gave us inbound BOXES and the public webhook, but an arriving message was recorded as a
-- luke_email_messages row carrying only from/to/subject. The "Review inbound email" task it
-- created therefore asked a human to review a body nobody had kept — the message text existed
-- only for the lifetime of the HTTP request. These two tables close that.
--
--   • luke_email_inbound        — the received content, 1:1 with a luke_email_messages row
--       (shared primary key). SEPARATE from luke_email_messages on purpose: bodies are large
--       and inbound-only, and luke_email_messages is the hot list/pagination table for the
--       whole capability. Attachments are stored as METADATA ONLY (name/type/length) — the
--       bytes belong in object storage (DOCUMENTS), never inline in a row or a process variable.
--   • luke_email_routing_rules  — ordered per-tenant (optionally per-box) rules matched against
--       an arriving message. First match wins; it decides who the review task goes to, how
--       urgent it is, whether a specific process runs instead, or that no task is made at all.
--
-- Under the postgres profile Hibernate ddl-auto is `none`, so this migration is the source of
-- truth; it must stay faithful to the JPA entities in
-- src/main/java/com/luke/engine/capability/email/ (InboundEmail, EmailRoutingRule).
-- Conventions mirror V1/V8/V9/V14: varchar(255) default strings, timestamp(6) for LocalDateTime,
-- boolean primitives, snake_case, indexes declared separately, IF NOT EXISTS for harmless re-runs.

    create table if not exists luke_email_inbound (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        box_id varchar(255),
        box_address varchar(255),
        mailbox_hash varchar(255),
        from_name varchar(255),
        to_full varchar(1000),
        cc_addresses varchar(1000),
        reply_to varchar(255),
        text_body text,
        html_body text,
        stripped_text_reply text,
        -- RFC 5322 Message-ID / In-Reply-To. 998 = the RFC 5322 maximum line length; a
        -- Message-ID is bounded by it, and varchar(255) silently truncates long ones from
        -- some mailers, which would break reply threading later.
        message_id_header varchar(998),
        in_reply_to varchar(998),
        -- JSON arrays: [{name,contentType,contentLength}] and [{name,value}].
        attachments text,
        headers text,
        attachment_count integer not null default 0,
        received_at timestamp(6) not null,
        primary key (id)
    );

    create index if not exists idx_emailinbound_tenant on luke_email_inbound (tenant_id);
    create index if not exists idx_emailinbound_box on luke_email_inbound (tenant_id, box_id);

    create table if not exists luke_email_routing_rules (
        id varchar(255) not null,
        tenant_id varchar(255) not null,
        -- NULL = applies to every inbound box for the tenant.
        box_id varchar(255),
        name varchar(255) not null,
        enabled boolean not null default true,
        -- NOT "position": it is a SQL standard function name, and H2 rejects it unquoted.
        sort_order integer not null default 0,
        match_field varchar(32) not null,
        match_operator varchar(32) not null,
        match_value varchar(1000) not null,
        case_sensitive boolean not null default false,
        action_assignee varchar(255),
        action_candidate_group varchar(255),
        action_priority integer,
        action_process_key varchar(255),
        action_task_name varchar(255),
        action_suppress_task boolean not null default false,
        created_at timestamp(6) not null,
        updated_at timestamp(6),
        primary key (id)
    );

    create index if not exists idx_emailrule_tenant on luke_email_routing_rules (tenant_id);
    create index if not exists idx_emailrule_order on luke_email_routing_rules (tenant_id, box_id, position);

-- Dedup. Postmark re-POSTs a webhook it believes failed, and a redelivered message must not
-- become a SECOND review task — duplicated work for a human is the visible symptom, and it is
-- the one failure mode a retry-happy provider guarantees you will eventually hit.
--
-- PARTIAL + GUARDED. Partial because outbound rows share this column and are not covered by
-- this rule. Guarded by a DO block because a unique index cannot be created over data that
-- already violates it: an environment that received duplicates before this migration would
-- otherwise fail to boot. Where that is true we skip the index and rely on the application's
-- pre-insert check alone (PublicInboundEmailController), which is what stops the sequential
-- retry — the index exists to close the concurrent-delivery race the check cannot.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM luke_email_messages
        WHERE direction = 'INBOUND' AND postmark_message_id IS NOT NULL
        GROUP BY tenant_id, postmark_message_id
        HAVING count(*) > 1
    ) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS idx_emailmsg_inbound_dedup
            ON luke_email_messages (tenant_id, postmark_message_id)
            WHERE direction = 'INBOUND' AND postmark_message_id IS NOT NULL;
    ELSE
        RAISE NOTICE 'luke_email_messages has pre-existing inbound duplicates; '
                     'skipping unique dedup index (application-level check still applies)';
    END IF;
END $$;
