CREATE TABLE user_two_factor (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    secret_ciphertext TEXT,
    pending_secret_ciphertext TEXT,
    pending_secret_expires_at TIMESTAMPTZ,
    last_totp_counter BIGINT NOT NULL DEFAULT -1,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    login_locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_two_factor_counter_check CHECK (last_totp_counter >= -1),
    CONSTRAINT user_two_factor_failed_attempts_check CHECK (failed_login_attempts >= 0),
    CONSTRAINT user_two_factor_pending_expiry_check
        CHECK ((pending_secret_ciphertext IS NULL) = (pending_secret_expires_at IS NULL))
);

CREATE TABLE user_two_factor_recovery_codes (
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    used_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, code_hash),
    CONSTRAINT user_two_factor_recovery_hash_check
        CHECK (code_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE two_factor_login_challenges (
    challenge_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT two_factor_login_attempts_check CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT two_factor_login_expiry_check CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX two_factor_login_challenges_user_idx
    ON two_factor_login_challenges (user_id);

CREATE INDEX two_factor_login_challenges_expiry_idx
    ON two_factor_login_challenges (expires_at);
