-- =========================================================
-- V19: Seed storefront reviews for every product
--
-- Three reviews per product (one per demo customer) on the
-- product's first non-deleted variant. Comments are generated
-- from the product name; ratings are 5/4/5.
--
-- Idempotent via uq_reviews_customer_variant: re-running inserts
-- nothing new. order_item_id stays NULL (reviews not tied to an order).
-- =========================================================

INSERT INTO reviews
    (review_id, variant_id, customer_id, order_item_id,
     review_rating, review_comment, created_at)
SELECT 'REV_' || p.id || '_' || t.n,
       v.id,
       c.id,
       NULL,
       t.rating,
       replace(t.comment, '{name}', p.product_name),
       now() - ((t.n * 5) || ' days')::interval
FROM products p
JOIN LATERAL (
    SELECT vv.id
    FROM variants vv
    WHERE vv.product_id = p.id
      AND vv.is_deleted = FALSE
    ORDER BY vv.id
    LIMIT 1
) v ON TRUE
JOIN (VALUES
    (1, 'CUS_cart-demo-1-example-com', 5,
     'Healthy, active {name} — settled in within a day and is feeding well.'),
    (2, 'CUS_cart-demo-2-example-com', 4,
     'Good color and size, packed well and arrived in great condition.'),
    (3, 'CUS_swagger-customer-example-com', 5,
     'Exactly as described. Now swimming happily in my planted tank.')
) AS t (n, customer_biz, rating, comment) ON TRUE
JOIN customers c ON c.customer_id = t.customer_biz
WHERE p.product_status <> 'DELETED'
ON CONFLICT (customer_id, variant_id) DO NOTHING;
