-- One business per database, matching existing email/Meta configuration.
BEGIN;
CREATE TABLE razorpay_config (
    config_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    auth_type VARCHAR(10) NOT NULL CHECK (auth_type IN ('OAUTH', 'KEYS')),
    mode VARCHAR(4) NOT NULL CHECK (mode IN ('test', 'live')),
    account_id TEXT,
    public_key TEXT NOT NULL,
    encrypted_secret TEXT NOT NULL,
    encrypted_refresh_token TEXT,
    access_expires_at TIMESTAMPTZ,
    encrypted_signature_secret TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    configured_by BIGINT NOT NULL REFERENCES users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX razorpay_one_active_config ON razorpay_config ((active)) WHERE active;
CREATE TABLE razorpay_oauth_state (
    state_hash TEXT PRIMARY KEY,
    admin_id BIGINT NOT NULL REFERENCES users(user_id),
    mode VARCHAR(4) NOT NULL CHECK (mode IN ('test', 'live')),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '10 minutes')
);
CREATE TABLE payment_request (
    request_id UUID PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES booking(booking_id),
    config_id BIGINT NOT NULL REFERENCES razorpay_config(config_id),
    initiated_by BIGINT NOT NULL REFERENCES users(user_id),
    amount_paise BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    pricing_cutoff TIMESTAMPTZ NOT NULL,
    duration_minutes BIGINT NOT NULL CHECK (duration_minutes >= 1),
    expires_at TIMESTAMPTZ NOT NULL,
    gateway_order_id TEXT UNIQUE,
    gateway_payment_link_id TEXT UNIQUE,
    payment_link_url TEXT,
    channel VARCHAR(10) NOT NULL DEFAULT 'CHECKOUT'
        CHECK (channel IN ('CHECKOUT', 'WHATSAPP', 'SMS', 'EMAIL')),
    status VARCHAR(20) NOT NULL DEFAULT 'CREATING'
        CHECK (status IN ('CREATING', 'PENDING', 'EXPIRED', 'PAID', 'REVIEW')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX payment_one_open_request ON payment_request(booking_id)
    WHERE status IN ('CREATING', 'PENDING');
CREATE INDEX payment_request_booking ON payment_request(booking_id);
CREATE TABLE payment_attempt (
    gateway_payment_id TEXT PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES payment_request(request_id),
    provider_status TEXT NOT NULL,
    provider_method TEXT,
    amount_paise BIGINT NOT NULL,
    currency TEXT NOT NULL,
    disposition VARCHAR(20) NOT NULL CHECK (disposition IN ('PENDING', 'FAILED', 'COMPLETED', 'REVIEW')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE payment_webhook_event (
    config_id BIGINT NOT NULL REFERENCES razorpay_config(config_id),
    event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (config_id, event_id)
);
CREATE TABLE payment_notification_outbox (
    booking_id BIGINT PRIMARY KEY REFERENCES booking(booking_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempted_at TIMESTAMPTZ
);
ALTER TABLE payment ADD COLUMN payment_request_id UUID REFERENCES payment_request(request_id);
ALTER TABLE payment ADD COLUMN gateway_payment_id TEXT;
ALTER TABLE payment ADD COLUMN gateway_method TEXT;
ALTER TABLE payment ALTER COLUMN payment_method DROP NOT NULL;
CREATE UNIQUE INDEX payment_gateway_payment_unique ON payment(gateway_payment_id)
    WHERE gateway_payment_id IS NOT NULL;
CREATE UNIQUE INDEX payment_request_success_unique ON payment(payment_request_id)
    WHERE payment_request_id IS NOT NULL;
-- Cash retains payment_method=CASH; online method is stored without restricting gateway choices.
COMMIT;
