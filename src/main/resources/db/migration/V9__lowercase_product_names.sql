-- =========================================================
-- V9: Canonicalize existing product names + enforce uniqueness.
--
-- ProductNames.normalize (create/update path) stores names trimmed,
-- whitespace-collapsed and lowercase. Seeds V6/V7 predate that rule and
-- keep Title Case, so lowercase them here to match.
--
-- Same transformation as the Java side minus the special-character
-- rejection: existing rows are not rewritten beyond case/whitespace.
-- =========================================================

UPDATE products
SET product_name = btrim(regexp_replace(lower(product_name), '[[:space:]]+', ' ', 'g')),
    updated_at   = now()
WHERE product_name <> btrim(regexp_replace(lower(product_name), '[[:space:]]+', ' ', 'g'));

-- Fail loudly instead of silently keeping two rows that now share a name.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM products GROUP BY product_name HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V9: duplicate product names after lowercasing; resolve them before migrating';
    END IF;
END $$;

-- DB-level backstop for the service check (covers concurrent creates and
-- direct repository writes). Case/whitespace collisions are caught here;
-- diacritic-only collisions ("cá neon" vs "ca neon") are caught by the
-- service via the derived product_id. Soft-deleted rows keep reserving
-- their name, matching ProductService.assertNameAvailable.
CREATE UNIQUE INDEX IF NOT EXISTS uq_products_name_lower
    ON products (LOWER(product_name));
