ALTER TABLE orders
    ADD COLUMN payment_expires_at TIMESTAMPTZ NULL,
    ADD COLUMN cancellation_reason VARCHAR(32) NULL,
    ADD COLUMN stock_released_at TIMESTAMPTZ NULL,
    ADD CONSTRAINT chk_orders_cancellation_reason CHECK (
        cancellation_reason IN ('STAFF_REJECTED', 'PAYMENT_EXPIRED', 'PAYMENT_FAILED')
    );

ALTER TABLE variants
    ADD COLUMN stock_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_variants_stock_revision CHECK (stock_revision >= 0);

-- Payment eligibility is rechecked under locks; a partial index cannot join payments.
CREATE INDEX idx_orders_payment_expiry ON orders (payment_expires_at, id)
    WHERE payment_expires_at IS NOT NULL
      AND stock_released_at IS NULL
      AND order_status IN ('PENDING', 'CONFIRMED', 'SHIPPING');
