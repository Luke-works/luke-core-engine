-- Per-version sign-off (forms design-time lifecycle). A checked-in version becomes
-- "signed off" once it passes its self-test; publishing is now gated on the TARGET
-- version being signed off, so the "tested" guarantee travels with the immutable
-- version instead of the mutable draft (which goes stale the moment you edit).
alter table luke_form_versions add column if not exists signed_off_at timestamp(6);
alter table luke_form_versions add column if not exists signed_off_by varchar(255);

-- Grandfather every version that predates this gate as signed off (at its check-in
-- time), so already-published forms stay publishable / roll-back-able. The gate only
-- bites versions checked in AFTER this migration.
update luke_form_versions set signed_off_at = checked_in_at where signed_off_at is null;
update luke_form_versions set signed_off_by = checked_in_by where signed_off_by is null;
