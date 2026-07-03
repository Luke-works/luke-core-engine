-- Route B M4 — embed token revocation.
-- The signed embed token carries this version; bumping it (POST .../embed-token/rotate) invalidates
-- every previously-issued token for the form, which then fails the version check on the public surface.
alter table luke_form_definitions add column if not exists embed_key_version integer not null default 0;
