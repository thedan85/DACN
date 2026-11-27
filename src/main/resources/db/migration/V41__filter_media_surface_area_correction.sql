-- V41: Correct V40 seed data.
--
-- V40 is already applied; editing it would break its Flyway checksum.
-- The seed instruction set both ceramic-ball products to Low surface
-- area, but the Taiwan Ceramic Balls row kept High. Point that product
-- at the existing surface-area/Low attribute value.
-- No schema change. Idempotent: the UPDATE is a no-op once corrected.

UPDATE public.product_attribute_values pav
SET attribute_value_id = av.id,
    updated_at = now()
FROM public.products p
JOIN public.attributes a ON a.attribute_id = 'surface-area'
JOIN public.attribute_values av
  ON av.attribute_id = a.id AND lower(av.attribute_value) = 'low'
WHERE pav.product_id = p.id
  AND pav.attribute_id = a.id
  AND p.product_slug = 'taiwan-ceramic-balls'
  AND pav.attribute_value_id <> av.id;
