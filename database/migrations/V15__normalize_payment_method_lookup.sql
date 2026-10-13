BEGIN;

CREATE TABLE payment_method (
    payment_method_id SMALLINT PRIMARY KEY,
    method_code VARCHAR(20) NOT NULL UNIQUE
);

INSERT INTO payment_method (payment_method_id, method_code) VALUES
    (1, 'CASH'),
    (2, 'RAZORPAY');

ALTER TABLE payment ADD COLUMN payment_method_id SMALLINT;

UPDATE payment p
SET payment_method_id = pm.payment_method_id
FROM payment_method pm
WHERE pm.method_code = CASE WHEN p.payment_method = 'CASH' THEN 'CASH' ELSE 'RAZORPAY' END;

ALTER TABLE payment
    ALTER COLUMN payment_method_id SET NOT NULL,
    ADD CONSTRAINT payment_payment_method_id_fkey
        FOREIGN KEY (payment_method_id) REFERENCES payment_method(payment_method_id);

ALTER TABLE payment
    DROP CONSTRAINT IF EXISTS payment_payment_method_check,
    DROP COLUMN payment_method;

COMMIT;
