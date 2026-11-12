
-- =========================================================
-- V31: Dashboard order-window index
--
-- The admin dashboard filters orders by created_at for the
-- current and previous window. The index keeps those range
-- scans proportional to the selected window instead of the
-- whole order history.
-- =========================================================

create index if not exists idx_orders_created_at
    on orders (created_at);
