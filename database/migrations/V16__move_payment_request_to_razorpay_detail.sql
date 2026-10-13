BEGIN;

UPDATE payment
SET payment_method_id = 2
WHERE payment_method_id <> 1;

DELETE FROM payment_method
WHERE payment_method_id NOT IN (1, 2);

ALTER TABLE payment_razorpay_detail
    ADD COLUMN request_id UUID;

UPDATE payment_razorpay_detail d
SET request_id = p.payment_request_id
FROM payment p
WHERE p.payment_id = d.payment_id;

ALTER TABLE payment_razorpay_detail
    ALTER COLUMN request_id SET NOT NULL,
    ADD CONSTRAINT payment_razorpay_detail_request_id_fkey
        FOREIGN KEY (request_id) REFERENCES payment_request(request_id);

DROP INDEX IF EXISTS payment_request_success_unique;

CREATE UNIQUE INDEX payment_request_success_unique
    ON payment_razorpay_detail(request_id);

ALTER TABLE payment
    DROP CONSTRAINT IF EXISTS payment_payment_request_id_fkey,
    DROP COLUMN payment_request_id;

COMMIT;
