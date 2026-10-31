-- =========================================================
-- V26: Order history index
--
-- Customer order history filters by customer_id and sorts by id
-- descending. The composite index serves both the page and the
-- count query (leading column), so it replaces the single-column
-- idx_orders_customer_id.
-- =========================================================

create index if not exists idx_orders_customer_id_id
    on orders (customer_id, id desc);

drop index if exists idx_orders_customer_id;
