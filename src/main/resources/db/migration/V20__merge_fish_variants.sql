-- =========================================================
-- V20: Merge colour-form fish listings into one product each
--
-- Tiger Barb, Cherry Barb, Penguin Tetra, Eggersi Killifish,
-- Rachovii Killifish and Honey Gourami were seeded as one product
-- per colour form (V18). Fold each colour form into its base
-- product as a variant:
--   * variant moves to the base product, keeps its reviews;
--   * its image becomes variant-scoped (thumbnail FK still points
--     at the base product's image);
--   * the now-empty wrapper product and its spec links go away;
--   * every variant gets a Color attribute (default = Normal,
--     except Eggersi where red is the default).
--
-- Variant business ids follow <base-slug>-<color>, so the default
-- variant stays <base-slug>-default and the storefront orders it
-- first (ProductService).
-- =========================================================

-- 1. Eggersi had no plain base product: its red form becomes the
--    species product, with red as the default variant.
UPDATE products
SET product_name = 'Eggersi Killifish',
    product_slug = 'eggersi-killifish',
    product_description = 'Small, colorful killifish available in red and blue color forms. Best kept as a pair or trio in a species tank.',
    product_detail_description = $d$Eggersi Killifish Care Guide

Small, colorful killifish available in red and blue color forms. Best kept as a pair or trio in a species tank.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Eggersi Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$
WHERE product_slug = 'eggersi-red-killifish';

-- 2. Rename merged/default variants so the business id and SKU
--    belong to the base product.
WITH rename(variant_id, new_variant_id, new_sku) AS (VALUES
    ('eggersi-red-killifish-default',      'eggersi-killifish-default',     'FISH-EGGERSI-KILLIFISH'),
    ('eggersi-blue-killifish-default',     'eggersi-killifish-blue',        'FISH-EGGERSI-KILLIFISH-BLUE'),
    ('gold-tiger-barb-default',            'tiger-barb-gold',               'FISH-TIGER-BARB-GOLD'),
    ('green-tiger-barb-default',           'tiger-barb-green',              'FISH-TIGER-BARB-GREEN'),
    ('albino-cherry-barb-default',         'cherry-barb-albino',            'FISH-CHERRY-BARB-ALBINO'),
    ('long-fin-cherry-barb-default',       'cherry-barb-long-fin',          'FISH-CHERRY-BARB-LONG-FIN'),
    ('red-tail-penguin-tetra-default',     'penguin-tetra-red-tail',        'FISH-PENGUIN-TETRA-RED-TAIL'),
    ('albino-rachovii-killifish-default',  'rachovii-killifish-albino',     'FISH-RACHOVII-KILLIFISH-ALBINO'),
    ('red-honey-gourami-default',          'honey-gourami-red',             'FISH-HONEY-GOURAMI-RED')
)
UPDATE variants v
SET variant_id = r.new_variant_id,
    variant_sku = r.new_sku
FROM rename r
WHERE v.variant_id = r.variant_id;

-- 3. Each source product has exactly one variant at this point, so
--    its product-scoped image can move to that variant. The product
--    thumbnail keeps pointing at the same media row.
UPDATE media m
SET variant_id = v.id,
    product_id = NULL
FROM products p
JOIN variants v ON v.product_id = p.id
WHERE m.product_id = p.id
  AND p.product_slug IN (
      'tiger-barb', 'gold-tiger-barb', 'green-tiger-barb',
      'cherry-barb', 'albino-cherry-barb', 'long-fin-cherry-barb',
      'penguin-tetra', 'red-tail-penguin-tetra',
      'eggersi-killifish', 'eggersi-blue-killifish',
      'rachovii-killifish', 'albino-rachovii-killifish',
      'honey-gourami', 'red-honey-gourami'
  );

-- 4. Move the colour variants under their base product.
WITH merge(extra_slug, base_slug) AS (VALUES
    ('gold-tiger-barb',           'tiger-barb'),
    ('green-tiger-barb',          'tiger-barb'),
    ('albino-cherry-barb',        'cherry-barb'),
    ('long-fin-cherry-barb',      'cherry-barb'),
    ('red-tail-penguin-tetra',    'penguin-tetra'),
    ('eggersi-blue-killifish',    'eggersi-killifish'),
    ('albino-rachovii-killifish', 'rachovii-killifish'),
    ('red-honey-gourami',         'honey-gourami')
)
UPDATE variants v
SET product_id = b.id
FROM merge m, products b, products e
WHERE b.product_slug = m.base_slug
  AND e.product_slug = m.extra_slug
  AND v.product_id = e.id;

-- 5. Delete the wrapper products. Their product_attribute_values
--    cascade; media and variants already moved.
DELETE FROM products
WHERE product_slug IN (
    'gold-tiger-barb', 'green-tiger-barb',
    'albino-cherry-barb', 'long-fin-cherry-barb',
    'red-tail-penguin-tetra',
    'eggersi-blue-killifish',
    'albino-rachovii-killifish',
    'red-honey-gourami'
);

-- 6. New Color values for the merged variants. Normal/Albino/Red/Blue
--    already exist from V6.
INSERT INTO attribute_values (attribute_value_id, attribute_id, attribute_value)
SELECT c.attribute_value_id, a.id, c.attribute_value
FROM (VALUES
    ('color-gold',     'Gold'),
    ('color-green',    'Green'),
    ('color-long-fin', 'Long Fin'),
    ('color-red-tail', 'Red Tail')
) AS c(attribute_value_id, attribute_value)
JOIN attributes a ON a.attribute_id = 'color'
ON CONFLICT DO NOTHING;

-- 7. Attach one Color per variant.
WITH color_map(variant_id, attribute_value_id) AS (VALUES
    ('tiger-barb-default',           'color-normal'),
    ('tiger-barb-gold',              'color-gold'),
    ('tiger-barb-green',             'color-green'),
    ('cherry-barb-default',          'color-normal'),
    ('cherry-barb-albino',           'color-albino'),
    ('cherry-barb-long-fin',         'color-long-fin'),
    ('penguin-tetra-default',        'color-normal'),
    ('penguin-tetra-red-tail',       'color-red-tail'),
    ('eggersi-killifish-default',    'color-red'),
    ('eggersi-killifish-blue',       'color-blue'),
    ('rachovii-killifish-default',   'color-normal'),
    ('rachovii-killifish-albino',    'color-albino'),
    ('honey-gourami-default',        'color-normal'),
    ('honey-gourami-red',            'color-red')
)
INSERT INTO variant_attribute_values
    (variant_value_id, variant_id, attribute_id, attribute_value_id)
SELECT cm.variant_id || '-color', v.id, a.id, av.id
FROM color_map cm
JOIN variants v ON v.variant_id = cm.variant_id
JOIN attributes a ON a.attribute_id = 'color'
JOIN attribute_values av
  ON av.attribute_id = a.id
 AND av.attribute_value_id = cm.attribute_value_id
ON CONFLICT (variant_id, attribute_id) DO UPDATE
SET attribute_value_id = EXCLUDED.attribute_value_id;
