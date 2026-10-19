-- =========================================================
-- V14: product_slug
--
-- Public product reads address products by a readable slug instead of the
-- internal product_id (PRD_<id>). The column is backfilled from product_name
-- with the same rules as BusinessId.slug on the Java side:
--   * lowercase, diacritics stripped, non-alphanumerics -> dashes
--   * duplicates get -2, -3, ... suffixes (names are not unique)
--   * an all-symbol name falls back to 'unnamed'
--
-- The unique index is on lower(product_slug), so lookups are
-- case-insensitive and soft-deleted products keep reserving their slug.
-- =========================================================

ALTER TABLE products ADD COLUMN product_slug VARCHAR(255);

DO $$
DECLARE
    r          RECORD;
    base_slug  TEXT;
    candidate  TEXT;
    suffix     INT;
BEGIN
    FOR r IN SELECT id, product_name FROM products ORDER BY id LOOP
        -- Vietnamese diacritics -> ASCII, then lowercase/slugify. lower() is
        -- applied first so the translate() map only needs lowercase letters.
        base_slug := lower(translate(
            r.product_name,
            'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ',
            'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd'
        ));
        base_slug := regexp_replace(base_slug, '[^a-z0-9]+', '-', 'g');
        base_slug := regexp_replace(base_slug, '(^-+|-+$)', '', 'g');
        IF base_slug = '' THEN
            base_slug := 'unnamed';
        END IF;

        -- "search" is a literal route under /api/v1/products and would shadow
        -- product detail lookup, so reserve it the same way ProductService does.
        IF base_slug = 'search' THEN
            candidate := base_slug || '-2';
            suffix := 3;
        ELSE
            candidate := base_slug;
            suffix := 2;
        END IF;
        WHILE EXISTS (
            SELECT 1 FROM products
            WHERE product_slug IS NOT NULL
              AND lower(product_slug) = lower(candidate)
        ) LOOP
            -- Truncate the base so base + "-N" still fits VARCHAR(255), and
            -- drop a dash that truncation may have left at the end.
            candidate := regexp_replace(
                    left(base_slug, 255 - length('-' || suffix)),
                    '-+$', ''
                ) || '-' || suffix;
            suffix := suffix + 1;
        END LOOP;

        UPDATE products SET product_slug = candidate WHERE id = r.id;
    END LOOP;
END $$;

ALTER TABLE products ALTER COLUMN product_slug SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_products_slug
    ON products (lower(product_slug));
