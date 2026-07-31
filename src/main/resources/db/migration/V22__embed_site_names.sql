-- Friendly labels for the origins in a form's embed allowlist ("Acme main site" beside
-- https://acme.com), so an author running several sites can tell the rows apart.
--
-- Deliberately a SEPARATE column from allowed_embed_origins. That column is the input to the CSP
-- frame-ancestors directive on the public embed surface, so its format is a security surface;
-- labels are decoration. Splitting them means naming a site can never alter the policy.
--
-- Nullable with no backfill: every existing form simply has no labels, which is exactly right —
-- there is nothing to infer from an origin, and an invented label would be worse than none.
ALTER TABLE luke_form_definition
    ADD COLUMN IF NOT EXISTS embed_origin_names text;
