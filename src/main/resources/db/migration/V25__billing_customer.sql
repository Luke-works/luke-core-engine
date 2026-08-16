-- Phase 2b Stripe billing: the Stripe customer ↔ tenant mapping, created on first checkout. Lets a
-- later customer.subscription.* webhook (which carries a Stripe customer id, not a tenant) resolve
-- back to the tenant, and lets a returning customer reuse their Stripe Customer. One row per tenant;
-- id IS the tenantId. NOT the source of truth for what a tenant pays — that stays luke_tenant_plan.
-- Postgres profile only (ddl-auto=none there); H2/tests build this from the BillingCustomer entity via
-- ddl-auto. Must stay faithful to the entity — PostgresSchemaValidationTest runs Hibernate validate
-- against a real Postgres + these migrations.
create table if not exists luke_billing_customer (
    id varchar(255) not null,
    stripe_customer_id varchar(255),
    stripe_subscription_id varchar(255),
    created_at timestamp(6) not null,
    updated_at timestamp(6),
    primary key (id)
);

create index if not exists idx_billing_customer_stripe on luke_billing_customer (stripe_customer_id);
