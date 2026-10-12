BEGIN;
ALTER TABLE payment_request ADD COLUMN next_reconcile_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE payment_request ADD COLUMN reconcile_errors INTEGER NOT NULL DEFAULT 0;
-- Existing requests keep their original deadline; new links use a 15-minute window.
COMMIT;
