CREATE TABLE zoho_cliq_bot_config (
    config_id SMALLINT PRIMARY KEY CHECK (config_id = 1),
    zoho_bot_id VARCHAR(128) NOT NULL UNIQUE,
    bot_unique_name VARCHAR(128) NOT NULL UNIQUE,
    bot_name VARCHAR(20) NOT NULL,
    description VARCHAR(300) NOT NULL,
    scope VARCHAR(20) NOT NULL CHECK (scope IN ('organization', 'team', 'personal')),
    message_handler_configured BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
