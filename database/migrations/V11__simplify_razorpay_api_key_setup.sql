-- Run after the existing migrations. API-key payments and credentials are retained.
BEGIN;

-- OAuth was never enabled. Stop if unexpected payment data depends on it.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM payment_request pr
        JOIN razorpay_config rc ON rc.config_id = pr.config_id
        WHERE rc.auth_type = 'OAUTH'
    ) THEN
        RAISE EXCEPTION 'OAuth configurations have payment records. Resolve these before removing OAuth.';
    END IF;
END $$;

DELETE FROM payment_webhook_event
WHERE config_id IN (SELECT config_id FROM razorpay_config WHERE auth_type = 'OAUTH');
DELETE FROM razorpay_config WHERE auth_type = 'OAUTH';
DROP TABLE IF EXISTS razorpay_oauth_state;

ALTER TABLE razorpay_config
    DROP COLUMN auth_type,
    DROP COLUMN mode,
    DROP COLUMN account_id,
    DROP COLUMN encrypted_refresh_token,
    DROP COLUMN access_expires_at,
    DROP COLUMN encrypted_signature_secret;

COMMIT;
