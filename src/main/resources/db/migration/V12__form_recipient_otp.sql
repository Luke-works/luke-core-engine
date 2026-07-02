-- Flyway V12 — outbound recipient OTP challenge.
--
-- One salted-hashed, expiring, attempt-capped one-time code per outbound FormInstance, mailed to the
-- recipient to prove control of their (preparer-asserted) email before they may open the form. Backs
-- the public /api/public/form-instances/{token} fill surface. Mirrors EmailVerification's "never store
-- the code in the clear" model. Faithful to FormRecipientOtp; postgres ddl-auto is `none`.

    create table if not exists luke_form_recipient_otp (
        id varchar(255) not null,
        attempts integer not null,
        code_hash varchar(255) not null,
        code_salt varchar(255) not null,
        created_at timestamp(6) not null,
        expires_at timestamp(6) not null,
        instance_id varchar(255) not null unique,
        primary key (id)
    );
