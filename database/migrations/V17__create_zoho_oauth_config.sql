CREATE TABLE zoho_oauth_client_config (
    config_id SMALLINT PRIMARY KEY CHECK (config_id = 1),
    client_id VARCHAR(255) NOT NULL,
    encrypted_client_secret TEXT NOT NULL,
    configured_by_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE zoho_oauth_connection (
    connection_id SMALLINT PRIMARY KEY CHECK (connection_id = 1),
    config_id SMALLINT NOT NULL REFERENCES zoho_oauth_client_config(config_id)
        ON DELETE CASCADE,
    zoho_org_id VARCHAR(128),
    connected_by_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    connected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE zoho_oauth_token (
    connection_id SMALLINT PRIMARY KEY REFERENCES zoho_oauth_connection(connection_id)
        ON DELETE CASCADE,
    encrypted_access_token TEXT NOT NULL,
    encrypted_refresh_token TEXT NOT NULL,
    access_token_expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE zoho_oauth_connection_scope (
    connection_id SMALLINT NOT NULL REFERENCES zoho_oauth_connection(connection_id)
        ON DELETE CASCADE,
    scope_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (connection_id, scope_name)
);
