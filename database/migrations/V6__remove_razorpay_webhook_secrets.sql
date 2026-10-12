-- Webhook signing secrets are supplied only by RAZORPAY_WEBHOOK_SECRET.
-- Also safe after the updated V5, which no longer creates these columns.
BEGIN;
ALTER TABLE razorpay_config DROP COLUMN IF EXISTS encrypted_webhook_secret;
ALTER TABLE razorpay_oauth_state DROP COLUMN IF EXISTS encrypted_webhook_secret;
COMMIT;
