-- V15: products.thumbnail_media_id -> media(id)
--
-- Thumbnail URLs lived twice (products.product_thumbnail and media.media_url).
-- Keep media as the only URL store; products now references a media row.
-- Existing URL values are backfilled into media rows when no matching row
-- (same product + URL) exists yet.

ALTER TABLE products ADD COLUMN thumbnail_media_id BIGINT NULL;

INSERT INTO media (media_id, media_url, product_id)
SELECT 'MED_' || substr(md5(random()::text || clock_timestamp()::text), 1, 8),
       p.product_thumbnail,
       p.id
FROM products p
WHERE p.product_thumbnail IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM media m
      WHERE m.product_id = p.id
        AND m.media_url = p.product_thumbnail
  );

UPDATE products p
SET thumbnail_media_id = (
    SELECT min(m.id)
    FROM media m
    WHERE m.product_id = p.id
      AND m.media_url = p.product_thumbnail
)
WHERE p.product_thumbnail IS NOT NULL;

ALTER TABLE products DROP COLUMN product_thumbnail;

ALTER TABLE products ADD CONSTRAINT fk_products_thumbnail_media
    FOREIGN KEY (thumbnail_media_id) REFERENCES media (id)
    ON DELETE SET NULL ON UPDATE CASCADE;

CREATE INDEX idx_products_thumbnail_media_id
    ON products (thumbnail_media_id);
