CREATE TABLE IF NOT EXISTS whatsapp_message_type (
    message_key VARCHAR(40) PRIMARY KEY
        CHECK (message_key IN (
            'OTP',
            'BOOKING_CONFIRMATION',
            'CHECKOUT_CONFIRMATION'
        )),
    display_name VARCHAR(80) NOT NULL,
    required_body_variable_count SMALLINT NOT NULL
        CHECK (required_body_variable_count >= 0),
    admin_assignable BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS whatsapp_message_template_assignment (
    message_key VARCHAR(40) PRIMARY KEY
        REFERENCES whatsapp_message_type(message_key) ON DELETE CASCADE,
    meta_template_id VARCHAR(128) NOT NULL,
    template_name VARCHAR(512) NOT NULL,
    language_code VARCHAR(20) NOT NULL,
    assigned_by_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO whatsapp_message_type
    (message_key, display_name, required_body_variable_count, admin_assignable)
VALUES
    ('OTP', 'OTP', 1, FALSE),
    ('BOOKING_CONFIRMATION', 'Booking confirmation', 5, TRUE),
    ('CHECKOUT_CONFIRMATION', 'Checkout confirmation', 4, TRUE)
ON CONFLICT (message_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    required_body_variable_count = EXCLUDED.required_body_variable_count,
    admin_assignable = EXCLUDED.admin_assignable;
