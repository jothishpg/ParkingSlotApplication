BEGIN;

ALTER TABLE password_reset_requests
    ADD COLUMN IF NOT EXISTS purpose VARCHAR(24) NOT NULL DEFAULT 'PASSWORD_RESET';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'password_reset_requests'
          AND column_name = 'reset_token_hash'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'password_reset_requests'
          AND column_name = 'action_token_hash'
    ) THEN
        ALTER TABLE password_reset_requests
            RENAME COLUMN reset_token_hash TO action_token_hash;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'password_reset_requests'
          AND column_name = 'reset_token_expires_at'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'password_reset_requests'
          AND column_name = 'action_token_expires_at'
    ) THEN
        ALTER TABLE password_reset_requests
            RENAME COLUMN reset_token_expires_at TO action_token_expires_at;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_prr_purpose'
          AND conrelid = 'password_reset_requests'::regclass
    ) THEN
        ALTER TABLE password_reset_requests
            ADD CONSTRAINT chk_prr_purpose
            CHECK (purpose IN ('PASSWORD_RESET', 'TOTP_RECOVERY'));
    END IF;
END;
$$;

COMMIT;
