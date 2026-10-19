-- =========================================================
-- V13: drop order_items.order_item_id (business id).
--
-- V12 already repointed reviews.order_item_id to order_items(id),
-- so the business id column and its UNIQUE constraint are unused.
-- =========================================================

ALTER TABLE order_items
    DROP CONSTRAINT order_items_order_item_id_key;

ALTER TABLE order_items
    DROP COLUMN order_item_id;
