-- =========================================================
-- V21: Refund-required payments; drop unused refresh token hash
--
-- A successful IPN can arrive after staff cancelled the order. The
-- money was collected, so the payment becomes REFUND_REQUIRED and
-- keeps the provider transaction id.
--
-- refresh_tokens.token_hash was never read (lookup is by token_id),
-- so the column and its unique constraint are dead weight.
-- =========================================================

ALTER TABLE payments DROP CONSTRAINT chk_payments_status;
ALTER TABLE payments ADD CONSTRAINT chk_payments_status
    CHECK (payment_status IN ('PENDING', 'CONFIRMED', 'FAILED', 'REFUNDED', 'REFUND_REQUIRED'));

ALTER TABLE refresh_tokens DROP COLUMN token_hash;
