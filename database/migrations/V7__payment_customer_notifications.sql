BEGIN;
ALTER TABLE payment_request ADD COLUMN customer_phone TEXT;
ALTER TABLE payment_request ADD COLUMN sms_consent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payment_request ADD COLUMN whatsapp_consent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payment_request ADD COLUMN consent_recorded_at TIMESTAMPTZ;
ALTER TABLE payment_request ADD COLUMN consent_recorded_by BIGINT REFERENCES users(user_id);
ALTER TABLE payment_request ADD COLUMN customer_token_hash TEXT UNIQUE;
CREATE TABLE payment_message_outbox (
    message_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES payment_request(request_id),
    event_key TEXT NOT NULL,
    channel TEXT NOT NULL,
    recipient TEXT NOT NULL,
    message_text TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'READY',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    provider_message_id TEXT,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    UNIQUE(request_id, event_key, channel)
);
CREATE INDEX payment_message_dispatch ON payment_message_outbox(next_attempt_at)
    WHERE status IN ('READY','PROCESSING');
COMMIT;
