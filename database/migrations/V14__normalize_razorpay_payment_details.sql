BEGIN;

CREATE TABLE payment_razorpay_detail (
    payment_id BIGINT PRIMARY KEY REFERENCES payment(payment_id) ON DELETE CASCADE,
    gateway_payment_id TEXT NOT NULL,
    gateway_method TEXT,
    CONSTRAINT payment_razorpay_detail_gateway_payment_unique UNIQUE (gateway_payment_id)
);

INSERT INTO payment_razorpay_detail (payment_id, gateway_payment_id, gateway_method)
SELECT payment_id, gateway_payment_id, gateway_method
FROM payment
WHERE gateway_payment_id IS NOT NULL OR gateway_method IS NOT NULL;

DROP INDEX IF EXISTS payment_gateway_payment_unique;

ALTER TABLE payment
    DROP COLUMN gateway_payment_id,
    DROP COLUMN gateway_method;

COMMIT;
