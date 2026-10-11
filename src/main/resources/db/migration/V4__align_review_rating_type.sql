-- =========================================================
-- V4: Align reviews.review_rating with JPA entity
--
-- V1 declared SMALLINT (ported from MySQL TINYINT) but
-- Review.rating is Integer, which Hibernate maps to INTEGER
-- (int4). Every other Integer field (variant_stock, quantity,
-- ...) already uses INTEGER, so widen the column rather than
-- touching Java. Widening conversion is lossless.
-- Keeps chk_reviews_rating (table-level CHECK, unaffected).
-- =========================================================

ALTER TABLE reviews ALTER COLUMN review_rating TYPE INTEGER;
