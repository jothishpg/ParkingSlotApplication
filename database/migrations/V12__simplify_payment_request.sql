BEGIN;

ALTER TABLE payment_request
    DROP COLUMN IF EXISTS currency,
    DROP COLUMN IF EXISTS channel,
    DROP COLUMN IF EXISTS created_at,
    DROP COLUMN IF EXISTS consent_recorded_at,
    DROP COLUMN IF EXISTS consent_recorded_by,
    DROP COLUMN IF EXISTS customer_token_hash;

COMMIT;
