-- =========================================================
-- V6: Seed fish products (tetra + badis) with variants and attributes
--
-- 6 products, 7 variants, 9 attributes.
-- Depends on V5 (categories 'tetra' and 'badis' must exist).
-- Business keys are deterministic slugs (same convention as V5), so
-- the script is idempotent via ON CONFLICT.
--
-- Product-level specs (product_attribute_values): Temperature, Temperament,
--   Care level, Aquarium level, Minimum tank size, Size, PH, Diet.
--   One value per attribute per product (uq_product_attribute).
--   attribute_values are shared across products and derived from the spec
--   list below (id = '<attribute_id>-<slugified value>'), so a typo in the
--   spec list can never produce a dangling reference.
-- Variant-level attribute: Color (only congo tetra has 2 variants).
-- Price/stock live ONLY on variants. PLACEHOLDER values (VND, stock 50):
--   edit before going live.
-- Thumbnails left NULL and no media rows (media_url is NOT NULL, URLs TBD).
-- Care values are taken from typical hobby care sheets and vary by source:
--   review before publishing.
-- NOTE: uq_product_attribute (product_id, attribute_id) is declared on the
--   JPA entity but was never created in the DB (V1 omits it), so backfill it
--   here — the final INSERT's ON CONFLICT arbiter requires it.
-- =========================================================

DO $$
BEGIN
    ALTER TABLE product_attribute_values
        ADD CONSTRAINT uq_product_attribute UNIQUE (product_id, attribute_id);
EXCEPTION WHEN duplicate_object THEN
    NULL;
END $$;

-- ---------- ATTRIBUTES ----------

INSERT INTO attributes (attribute_id, attribute_name) VALUES
    ('temperature',       'Temperature'),
    ('temperament',       'Temperament'),
    ('care-level',        'Care level'),
    ('aquarium-level',    'Aquarium level'),
    ('minimum-tank-size', 'Minimum tank size'),
    ('size',              'Size'),
    ('ph',                'PH'),
    ('diet',              'Diet'),
    ('color',             'Color')
ON CONFLICT (attribute_id) DO UPDATE SET
    attribute_name = EXCLUDED.attribute_name;

-- ---------- PRODUCTS ----------
-- thumbnail/status intentionally not overwritten on conflict.

INSERT INTO products (product_id, product_name, product_description, product_thumbnail, product_status, category_id) VALUES
    ('ember-tetra',
     'Ember Tetra',
     'Tiny, peaceful nano schooling fish with a glowing orange-red body. Best kept in a group of 8 or more in a planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('red-cherry-tetra',
     'Red Cherry Tetra',
     'Peaceful schooling tetra with deep red coloration. Looks best in a group in a planted tank with a dark substrate.',
     NULL, 'ACTIVE', 'tetra'),
    ('raccoon-tetra',
     'Raccoon Tetra',
     'Peaceful schooling tetra with a rosy-gold body and bold black markings. Keep in a group of 6 or more.',
     NULL, 'ACTIVE', 'tetra'),
    ('congo-tetra',
     'Congo Tetra',
     'Large, shimmering African tetra with long flowing fins. Needs a spacious tank, a school of 6 or more and calm tank mates.',
     NULL, 'ACTIVE', 'tetra'),
    ('dario-dario',
     'Dario Dario',
     'Scarlet badis: a tiny, vividly red and blue micro predator from India. Prefers live or frozen foods and a mature, densely planted tank.',
     NULL, 'ACTIVE', 'badis'),
    ('badis-badis',
     'Badis Badis',
     'Blue badis: a small micro predator with iridescent blue colors. Males can be territorial, so give it plenty of cover in a planted tank.',
     NULL, 'ACTIVE', 'badis')
ON CONFLICT (product_id) DO UPDATE SET
    product_name        = EXCLUDED.product_name,
    product_description = EXCLUDED.product_description,
    category_id         = EXCLUDED.category_id;

-- ---------- VARIANTS ----------
-- price/stock not overwritten on conflict (they change at runtime).

INSERT INTO variants (variant_id, variant_sku, variant_price, variant_stock, product_id) VALUES
    ('ember-tetra-default',       'FISH-EMBER-TETRA',        15000.00, 50, 'ember-tetra'),
    ('red-cherry-tetra-default',  'FISH-RED-CHERRY-TETRA',   15000.00, 50, 'red-cherry-tetra'),
    ('raccoon-tetra-default',     'FISH-RACCOON-TETRA',      25000.00, 50, 'raccoon-tetra'),
    ('congo-tetra-default',       'FISH-CONGO-TETRA',        60000.00, 50, 'congo-tetra'),
    ('congo-tetra-albino',        'FISH-CONGO-TETRA-ALBINO', 90000.00, 50, 'congo-tetra'),
    ('dario-dario-default',       'FISH-DARIO-DARIO',        50000.00, 50, 'dario-dario'),
    ('badis-badis-default',       'FISH-BADIS-BADIS',        45000.00, 50, 'badis-badis')
ON CONFLICT (variant_id) DO UPDATE SET
    variant_sku = EXCLUDED.variant_sku,
    product_id  = EXCLUDED.product_id;

-- ---------- VARIANT ATTRIBUTE VALUES (Color) ----------

INSERT INTO attribute_values (attribute_value_id, attribute_id, attribute_value) VALUES
    ('color-normal', 'color', 'Normal'),
    ('color-albino', 'color', 'Albino')
ON CONFLICT (attribute_id, attribute_value_id) DO UPDATE SET
    attribute_value = EXCLUDED.attribute_value;

INSERT INTO variant_attribute_values (variant_value_id, variant_id, attribute_id, attribute_value_id) VALUES
    ('congo-tetra-default-color', 'congo-tetra-default', 'color', 'color-normal'),
    ('congo-tetra-albino-color',  'congo-tetra-albino',  'color', 'color-albino')
ON CONFLICT (variant_id, attribute_id) DO UPDATE SET
    attribute_value_id = EXCLUDED.attribute_value_id;

-- ---------- PRODUCT ATTRIBUTE VALUES (fish specs) ----------
-- One statement: derive shared attribute_values from the spec list, then
-- link each product to its (attribute_id, attribute_value_id) pair.

WITH spec (product_id, attribute_id, attribute_value) AS (VALUES
    -- Ember Tetra
    ('ember-tetra', 'temperature',       '23-29 °C'),
    ('ember-tetra', 'temperament',       'Peaceful'),
    ('ember-tetra', 'care-level',        'Easy'),
    ('ember-tetra', 'aquarium-level',    'Middle'),
    ('ember-tetra', 'minimum-tank-size', '40 L'),
    ('ember-tetra', 'size',              '2 cm'),
    ('ember-tetra', 'ph',                '5.5-7.0'),
    ('ember-tetra', 'diet',              'Omnivore'),
    -- Red Cherry Tetra
    ('red-cherry-tetra', 'temperature',       '22-28 °C'),
    ('red-cherry-tetra', 'temperament',       'Peaceful'),
    ('red-cherry-tetra', 'care-level',        'Easy'),
    ('red-cherry-tetra', 'aquarium-level',    'Middle'),
    ('red-cherry-tetra', 'minimum-tank-size', '40 L'),
    ('red-cherry-tetra', 'size',              '2.5-4 cm'),
    ('red-cherry-tetra', 'ph',                '6.0-7.5'),
    ('red-cherry-tetra', 'diet',              'Omnivore'),
    -- Raccoon Tetra
    ('raccoon-tetra', 'temperature',       '22-28 °C'),
    ('raccoon-tetra', 'temperament',       'Peaceful'),
    ('raccoon-tetra', 'care-level',        'Easy'),
    ('raccoon-tetra', 'aquarium-level',    'Middle'),
    ('raccoon-tetra', 'minimum-tank-size', '75 L'),
    ('raccoon-tetra', 'size',              '3-5 cm'),
    ('raccoon-tetra', 'ph',                '6.0-7.0'),
    ('raccoon-tetra', 'diet',              'Omnivore'),
    -- Congo Tetra
    ('congo-tetra', 'temperature',       '22-28 °C'),
    ('congo-tetra', 'temperament',       'Peaceful'),
    ('congo-tetra', 'care-level',        'Moderate'),
    ('congo-tetra', 'aquarium-level',    'Middle'),
    ('congo-tetra', 'minimum-tank-size', '150 L'),
    ('congo-tetra', 'size',              '7-9 cm'),
    ('congo-tetra', 'ph',                '6.0-7.5'),
    ('congo-tetra', 'diet',              'Omnivore'),
    -- Dario Dario
    ('dario-dario', 'temperature',       '20-26 °C'),
    ('dario-dario', 'temperament',       'Peaceful'),
    ('dario-dario', 'care-level',        'Moderate'),
    ('dario-dario', 'aquarium-level',    'Bottom'),
    ('dario-dario', 'minimum-tank-size', '40 L'),
    ('dario-dario', 'size',              '2 cm'),
    ('dario-dario', 'ph',                '6.5-7.5'),
    ('dario-dario', 'diet',              'Carnivore'),
    -- Badis Badis
    ('badis-badis', 'temperature',       '22-26 °C'),
    ('badis-badis', 'temperament',       'Semi-aggressive'),
    ('badis-badis', 'care-level',        'Moderate'),
    ('badis-badis', 'aquarium-level',    'Bottom'),
    ('badis-badis', 'minimum-tank-size', '60 L'),
    ('badis-badis', 'size',              '5-6 cm'),
    ('badis-badis', 'ph',                '6.0-7.5'),
    ('badis-badis', 'diet',              'Carnivore')
),
vals AS (
    INSERT INTO attribute_values (attribute_value_id, attribute_id, attribute_value)
    SELECT DISTINCT
        attribute_id || '-' || trim(both '-' from regexp_replace(lower(attribute_value), '[^a-z0-9]+', '-', 'g')),
        attribute_id,
        attribute_value
    FROM spec
    ON CONFLICT (attribute_id, attribute_value_id) DO UPDATE SET
        attribute_value = EXCLUDED.attribute_value
    RETURNING attribute_value_id, attribute_id, attribute_value
)
INSERT INTO product_attribute_values (product_value_id, product_id, attribute_id, attribute_value_id)
SELECT s.product_id || '-' || s.attribute_id,
       s.product_id,
       s.attribute_id,
       v.attribute_value_id
FROM spec s
JOIN vals v
  ON v.attribute_id = s.attribute_id
 AND v.attribute_value = s.attribute_value
ON CONFLICT (product_id, attribute_id) DO UPDATE SET
    attribute_value_id = EXCLUDED.attribute_value_id;