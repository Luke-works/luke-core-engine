-- Optimistic-lock version backfill (#57 follow-up).
--
-- luke_form_definitions.version is the JPA @Version optimistic-lock counter, but the baseline (V1)
-- created it NULLABLE with no default (every OTHER version column is NOT NULL — this one was the
-- oversight). Rows that predate the @Version field — existing dev/qa/prod data built by the old
-- ddl-auto:update regime, before this Flyway baseline — carry version = NULL. Such a row SELECTs
-- fine (so the form opens) but FAILS every UPDATE: Hibernate's versioned UPDATE can't increment /
-- match a null version, and the resulting error is NOT an OptimisticLockingFailureException, so it
-- escapes the controller's 409 handler and surfaces as a 500 on every draft save / meta edit.
--
-- Backfill the nulls to 0, then make the column NOT NULL DEFAULT 0 so it matches the @Version
-- contract and the failure can't recur. The table is small, so the rewrite/lock is negligible.
update luke_form_definitions set version = 0 where version is null;
alter table luke_form_definitions alter column version set default 0;
alter table luke_form_definitions alter column version set not null;
