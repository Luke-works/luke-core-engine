/**
 * KEEP-ON-MERGE package for the e-signature ("SIGNATURES") capability.
 *
 * <p>Everything here — entities, repositories, the three SPIs
 * ({@code SignatureProvider} / {@code TrustProvider} / {@code DocumentStore}), their default
 * impls, IP-tracking / verification helpers, and the REST controllers — is written to be
 * copied UNCHANGED into {@code luke-core-engine} at SIG-M. Filled in by SIG-1..SIG-3.
 *
 * <p>Contrast {@code com.luke.signature.*}, the standalone shell (main class, dev auth
 * filter, CORS, {@code /health}, standalone config) which is DELETED at merge.
 *
 * <p>Merge-ready rules: tables prefixed {@code luke_signature_*}; identity from
 * {@code X-Tenant-Id} / {@code X-User-Id} headers; entities kept DB-PORTABLE (no native SQL,
 * no Postgres-only DDL, avoid reserved words) so the H2&rarr;Postgres swap at SIG-M is a
 * datasource change plus a smoke test, not a rewrite.
 */
package com.luke.engine.capability.signature;
