CREATE TABLE user_profile_change (
    change_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    updated_value VARCHAR(32) NOT NULL,
    old_value TEXT NOT NULL,
    new_value TEXT NOT NULL,
    time_of_update TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX user_profile_change_user_time_idx
    ON user_profile_change (user_id, time_of_update DESC);
