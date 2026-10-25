-- =========================================================
-- V10: product_id becomes PRD_<id>, and names stop being unique.
--
-- ProductService.create assigns PRD_<id> right after the identity id is
-- generated. This migration does the same for rows that predate that rule
-- (V6/V7 seeds used a readable slug, V9 stayed as-is), and drops the name
-- uniqueness backstop added by V9: product names are display-only and may
-- legitimately repeat (same fish sold by two suppliers, etc.).
-- =========================================================

-- product_attribute_values has no FK to products, so ON UPDATE CASCADE does
-- not reach it; rewrite it explicitly before the products update.
UPDATE product_attribute_values pav
SET product_id       = 'PRD_' || p.id,
    product_value_id = 'PRD_' || p.id || '-' || pav.attribute_id
FROM products p
WHERE pav.product_id = p.product_id;

-- variants.product_id and media.product_id are ON UPDATE CASCADE, so this
-- single statement carries the children along.
UPDATE products SET product_id = 'PRD_' || id;

-- Names are display-only now; drop V9's uniqueness backstop.
DROP INDEX IF EXISTS uq_products_name_lower;
