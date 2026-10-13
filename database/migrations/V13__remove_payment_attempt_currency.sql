BEGIN;

ALTER TABLE payment_attempt
    DROP COLUMN IF EXISTS currency;

COMMIT;
