-- =========================================================
-- V33: VietQR payment method
--
-- VietQR is a distinct method; BANK_TRANSFER keeps its
-- existing meaning.
-- =========================================================

ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payments_method;
ALTER TABLE payments ADD CONSTRAINT chk_payments_method
    CHECK (payment_method IN
        ('CREDIT_CARD', 'DEBIT_CARD', 'BANK_TRANSFER', 'E_WALLET', 'COD', 'VIETQR'));
