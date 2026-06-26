-- Route B M2 — per-form clickjacking allowlist.
-- The set of web origins permitted to embed this form's published version, enforced as the
-- CSP `frame-ancestors` directive on the public embed surface. Stored as a comma-separated list
-- of origins, e.g. "https://acme.com,https://*.acme.com:8443".
-- NULL / empty = embeddable by ANY site (public default); set to restrict who may frame the form.
alter table luke_form_definitions add column if not exists allowed_embed_origins text;
