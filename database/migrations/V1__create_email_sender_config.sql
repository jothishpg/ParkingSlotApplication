CREATE TABLE email_sender_config (
    config_id SMALLINT PRIMARY KEY CHECK (config_id = 1),
    sender_email VARCHAR(320) NOT NULL,
    encrypted_refresh_token TEXT NOT NULL,
    encrypted_access_token TEXT,
    access_token_expires_at TIMESTAMPTZ,
    granted_scopes TEXT NOT NULL,
    configured_by_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
