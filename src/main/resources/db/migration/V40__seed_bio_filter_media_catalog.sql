-- =========================================================
-- V40: Seed the Cloudinary "consumables/filter media" catalog
--
-- Source: Cloudinary consumables/filter media/*
--   bio (9 images):       9 products in category 'bio'
--   chemical (2 images):  2 products in category 'chemical'
--   mechanical (2 images): 2 products in category 'mechanical'
-- All three categories already exist from V5 under 'filter-media'.
-- Specs:
--   bio       -> surface area, material, pH effect
--   chemical  -> purpose, reusable, service life, pH effect
--   mechanical -> filtration level, reusable, service life, pH effect
-- A dedicated 'ph-effect' attribute is used because the existing 'ph'
-- attribute holds fish pH tolerance ranges such as 5.5-7.0.
-- Prices/stock are PLACEHOLDERS (VND, stock 50) like V6/V18/V27.
-- Idempotent: product_slug/variant_id/media_url guards.
-- =========================================================

DROP TABLE IF EXISTS tmp_seed_bio;
DROP TABLE IF EXISTS tmp_seed_chemical;
DROP TABLE IF EXISTS tmp_seed_mechanical;

CREATE TEMP TABLE tmp_seed_bio (
    slug            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    category_id     TEXT NOT NULL,
    description     TEXT NOT NULL,
    detail          TEXT NOT NULL,
    price           NUMERIC(10,2) NOT NULL,
    surface_area    TEXT NOT NULL,
    material        TEXT NOT NULL,
    ph_effect       TEXT NOT NULL,
    image_url       TEXT NOT NULL
);

CREATE TEMP TABLE tmp_seed_chemical (
    slug         TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    category_id  TEXT NOT NULL,
    description  TEXT NOT NULL,
    detail       TEXT NOT NULL,
    price        NUMERIC(10,2) NOT NULL,
    purpose      TEXT NOT NULL,
    reusable     TEXT NOT NULL,
    service_life TEXT NOT NULL,
    ph_effect    TEXT NOT NULL,
    image_url    TEXT NOT NULL
);

CREATE TEMP TABLE tmp_seed_mechanical (
    slug             TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    category_id      TEXT NOT NULL,
    description      TEXT NOT NULL,
    detail           TEXT NOT NULL,
    price            NUMERIC(10,2) NOT NULL,
    filtration_level TEXT NOT NULL,
    reusable         TEXT NOT NULL,
    service_life     TEXT NOT NULL,
    ph_effect        TEXT NOT NULL,
    image_url        TEXT NOT NULL
);

INSERT INTO tmp_seed_bio
    (slug, name, category_id, description, detail, price,
     surface_area, material, ph_effect, image_url)
VALUES
('lava-rock', 'Lava Rock', 'bio',
 $d$Porous volcanic rock with a rough, irregular surface. A durable natural biomedia for established filters.$d$,
 $d$Lava Rock Filter Media

Rinse thoroughly before use to remove loose dust. Place it in a filter chamber with good water flow, or use it as a base layer under aquasoil.

Surface area is low compared with ceramic biomedia, so pair it with a higher-surface medium in high-stock tanks. It is inert and does not shift pH.$d$,
 25000.00, 'Low', 'Natural', 'Lower',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793280634/lava_rock'),

('seachem-matrix', 'Seachem Matrix', 'bio',
 $d$Highly porous mineral biomedia with a very large internal surface area. Suitable for freshwater and marine filtration.$d$,
 $d$Seachem Matrix Care Guide

Rinse gently before first use and place it where water flows through the media chamber. Do not replace all media at once; move established media into the new filter to keep the cycle stable.

Matrix provides a very high surface area for nitrifying bacteria. It is inert and does not change pH.$d$,
 150000.00, 'Very high', 'Natural', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793280602/seachem_matrix'),

('neo-media', 'Neo Media', 'bio',
 $d$Sintered ceramic biomedia with a dense pore structure. Designed for long-term biological filtration in canister and hang-on-back filters.$d$,
 $d$Neo Media Care Guide

Rinse before use and pack loosely so water can pass through the media bed. Leave part of the established media in place during maintenance to avoid a cycle crash.

Very high surface area with no meaningful effect on water chemistry.$d$,
 120000.00, 'Very high', 'Artificial', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793280585/neo_media'),

('maifan-ring', 'Maifan Ring', 'bio',
 $d$Porous natural stone rings that host nitrifying bacteria. A simple drop-in biomedia for most filter types.$d$,
 $d$Maifan Ring Care Guide

Rinse the rings before adding them to the filter chamber. Spread them across the flow path rather than packing them tightly.

Medium surface area. The stone is largely inert; monitor pH when used in soft, low-buffer water.$d$,
 45000.00, 'Medium', 'Natural', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793280531/maifan_ring'),

('nitro-ring', 'Nitro Ring', 'bio',
 $d$Ceramic rings with a hollow core and porous body. Made to maximize colonizable surface in a compact filter space.$d$,
 $d$Nitro Ring Care Guide

Rinse before first use. Fill the media chamber loosely and keep some older rings when cleaning so the bacterial colony survives.

Very high surface area, inert in water, with no direct pH effect.$d$,
 90000.00, 'Very high', 'Artificial', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793280433/nitro_ring'),

('juntong', 'Juntong Bio Media', 'bio',
 $d$Hydrophilic porous bio-media cylinders with a high internal pore count. Sold as a complete filter media block.$d$,
 $d$Juntong Bio Media Care Guide

Rinse the block lightly before installation. Place it in the main flow path of a canister or sump and avoid sealing it against the chamber wall.

Very high surface area and inert, so pH stays stable when it is used alongside regular water changes.$d$,
 110000.00, 'Very high', 'Artificial', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793280295/juntong'),

('yellow-ceramic-balls', 'Yellow Ceramic Balls', 'bio',
 $d$Small yellow ceramic media balls for biological filtration. Easy to distribute across a filter basket.$d$,
 $d$Yellow Ceramic Balls Care Guide

Rinse before use. Spread the balls through the media chamber and keep a portion of the old media when servicing the filter.

Low surface area compared with rings and rods. Best used as a supplement rather than the only biomedia. Inert with no pH effect.$d$,
 35000.00, 'Low', 'Artificial', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793280133/yellow_ceramic_balls'),

('taiwan-ceramic-balls', 'Taiwan Ceramic Balls', 'bio',
 $d$Ceramic ball biomedia with a porous outer shell. A common choice for canister and hang-on-back filters.$d$,
 $d$Taiwan Ceramic Balls Care Guide

Rinse before adding to the filter. Use enough to fill the chamber without blocking flow, and retain some established media during cleaning.

High surface area and inert. No direct pH effect.$d$,
 40000.00, 'High', 'Artificial', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793280074/taiwan_ceramic_balls'),

('bio-home', 'Biohome', 'bio',
 $d$Sintered ceramic media sticks with a very high internal surface area. Intended for long-term biological filtration.$d$,
 $d$Biohome Care Guide

Rinse lightly before first use and arrange the sticks so water moves between them. During filter maintenance, keep part of the colonized media in the tank water.

Very high surface area and chemically inert. Use with regular water changes to keep nitrate under control.$d$,
 130000.00, 'Very high', 'Artificial', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793279938/bio_home');

INSERT INTO tmp_seed_chemical
    (slug, name, category_id, description, detail, price,
     purpose, reusable, service_life, ph_effect, image_url)
VALUES
('activated-carbon', 'Activated Carbon', 'chemical',
 $d$Activated carbon pellets that adsorb toxins, odors and discoloration. A single-use chemical medium for clearer water.$d$,
 $d$Activated Carbon Care Guide

Rinse the carbon before use to remove dust, then place it in a media bag or a filter chamber with steady water flow. Use it after medication treatments or when water smells musty or looks yellow.

Carbon is not reusable. Replace it after roughly two to four weeks, or sooner in a heavily stocked tank. It is inert in normal use and does not shift pH.$d$,
 45000.00, 'Toxin removal', 'No', 'Short', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793281241/Activated_Carbon'),

('purifying-resin', 'Purifying Resin', 'chemical',
 $d$Porous synthetic resin beads that bind organic waste and polish aquarium water. Supplied in a filter bag for easy placement.$d$,
 $d$Purifying Resin Care Guide

Rinse the bag before use and place it where water flows through the filter chamber. The resin removes dissolved organics that cause yellow water and can reduce ammonia, nitrite and nitrate spikes.

Unlike carbon, the resin can be regenerated when exhausted; follow the product regeneration instructions rather than discarding it. It is inert and does not change pH.$d$,
 120000.00, 'Water polishing', 'Yes', 'Long', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793281235/purigen');

INSERT INTO tmp_seed_mechanical
    (slug, name, category_id, description, detail, price,
     filtration_level, reusable, service_life, ph_effect, image_url)
VALUES
('cashmere-filter-pads', 'YEE Cashmere Filter Pads', 'mechanical',
 $d$Soft cashmere filter cotton pads that trap fine debris and polish water. Supplied by YEE in 250g and 120g packs.$d$,
 $d$YEE Cashmere Filter Pads Care Guide

Rinse the pads before first use and place them in a filter chamber or media bag where water flows through them. Replace them when they clog or lose shape rather than trying to wash them clean.

Fine filtration for clear water. The pads are inert and do not change pH.$d$,
 35000.00, 'Fine', 'No', 'Short', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793281131/cashmere'),

('8d-filter-mat', '8D Filter Mat', 'mechanical',
 $d$Multi-layer blue and white filter mat with an open mesh structure. Designed to catch larger debris and extend filter media life.$d$,
 $d$8D Filter Mat Care Guide

Cut the mat to fit the filter chamber and place the coarser side toward the incoming water. Rinse it in removed tank water during maintenance instead of replacing it each time.

Coarse mechanical filtration that is washable and reusable. It is inert and does not affect pH.$d$,
 85000.00, 'Coarse', 'Yes', 'Long', 'Neutral',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793280931/matt_8d');

-- 1. ATTRIBUTES: filter-media specs not covered by the fish/plant seeds.
INSERT INTO attributes (attribute_id, attribute_name) VALUES
    ('surface-area', 'Surface area'),
    ('material',      'Material'),
    ('ph-effect',     'pH effect'),
    ('purpose',       'Purpose'),
    ('reusable',      'Reusable'),
    ('service-life',  'Service life'),
    ('filtration-level', 'Filtration level')
ON CONFLICT (attribute_id) DO UPDATE SET
    attribute_name = EXCLUDED.attribute_name;

-- 2. PRODUCTS: temporary business id, rekeyed to PRD_<id> after insert.
INSERT INTO products
    (product_id, product_name, product_description, product_detail_description,
     product_status, category_id, product_slug)
SELECT 'seed:' || s.slug, s.name, s.description, s.detail,
       'ACTIVE', c.id, s.slug
FROM (
    SELECT slug, name, category_id, description, detail FROM tmp_seed_bio
    UNION ALL
    SELECT slug, name, category_id, description, detail FROM tmp_seed_chemical
    UNION ALL
    SELECT slug, name, category_id, description, detail FROM tmp_seed_mechanical
) s
JOIN categories c ON c.category_id = s.category_id
WHERE NOT EXISTS (
    SELECT 1 FROM products p WHERE lower(p.product_slug) = lower(s.slug)
);

UPDATE products
SET product_id = 'PRD_' || id
WHERE product_id LIKE 'seed:%';

-- 3. VARIANTS: one default variant per new product. SKU prefix follows
-- the subcategory so the three groups cannot collide.
INSERT INTO variants (variant_id, variant_sku, variant_price, variant_stock, product_id)
SELECT s.slug || '-default',
       CASE s.category_id
           WHEN 'bio' THEN 'BIO-'
           WHEN 'chemical' THEN 'CHEM-'
           ELSE 'MECH-'
       END || upper(s.slug),
       s.price, 50, p.id
FROM (
    SELECT slug, category_id, price FROM tmp_seed_bio
    UNION ALL
    SELECT slug, category_id, price FROM tmp_seed_chemical
    UNION ALL
    SELECT slug, category_id, price FROM tmp_seed_mechanical
) s
JOIN products p ON lower(p.product_slug) = lower(s.slug)
WHERE NOT EXISTS (
    SELECT 1 FROM variants v WHERE lower(v.variant_id) = lower(s.slug || '-default')
);

-- 4. MEDIA: one product-scoped image per new product, then thumbnail FK.
INSERT INTO media (media_id, media_url, product_id, is_deleted)
SELECT 'MED_' || s.slug, s.image_url, p.id, FALSE
FROM (
    SELECT slug, image_url FROM tmp_seed_bio
    UNION ALL
    SELECT slug, image_url FROM tmp_seed_chemical
    UNION ALL
    SELECT slug, image_url FROM tmp_seed_mechanical
) s
JOIN products p ON lower(p.product_slug) = lower(s.slug)
WHERE NOT EXISTS (
    SELECT 1 FROM media m WHERE m.product_id = p.id AND m.media_url = s.image_url
);

UPDATE products p
SET thumbnail_media_id = (
    SELECT min(m.id) FROM media m WHERE m.product_id = p.id
)
WHERE p.thumbnail_media_id IS NULL
  AND (
      EXISTS (SELECT 1 FROM tmp_seed_bio s WHERE lower(s.slug) = lower(p.product_slug))
      OR EXISTS (SELECT 1 FROM tmp_seed_chemical s WHERE lower(s.slug) = lower(p.product_slug))
      OR EXISTS (SELECT 1 FROM tmp_seed_mechanical s WHERE lower(s.slug) = lower(p.product_slug))
  );

-- 5. ATTRIBUTE VALUES: shared (attribute, value) pairs for the seven specs.
WITH spec AS (
    SELECT slug, 'surface-area' AS attribute_id, surface_area AS value FROM tmp_seed_bio
    UNION ALL SELECT slug, 'material',      material     FROM tmp_seed_bio
    UNION ALL SELECT slug, 'ph-effect',     ph_effect    FROM tmp_seed_bio
    UNION ALL SELECT slug, 'purpose',      purpose      FROM tmp_seed_chemical
    UNION ALL SELECT slug, 'reusable',     reusable     FROM tmp_seed_chemical
    UNION ALL SELECT slug, 'service-life', service_life FROM tmp_seed_chemical
    UNION ALL SELECT slug, 'ph-effect',    ph_effect    FROM tmp_seed_chemical
    UNION ALL SELECT slug, 'filtration-level', filtration_level FROM tmp_seed_mechanical
    UNION ALL SELECT slug, 'reusable',         reusable         FROM tmp_seed_mechanical
    UNION ALL SELECT slug, 'service-life',     service_life     FROM tmp_seed_mechanical
    UNION ALL SELECT slug, 'ph-effect',        ph_effect        FROM tmp_seed_mechanical
)
INSERT INTO attribute_values (attribute_value_id, attribute_id, attribute_value)
SELECT DISTINCT
       s.attribute_id || '-' || trim(both '-' from regexp_replace(lower(s.value), '[^a-z0-9]+', '-', 'g')),
       a.id,
       s.value
FROM spec s
JOIN attributes a ON a.attribute_id = s.attribute_id
WHERE NOT EXISTS (
    SELECT 1 FROM attribute_values av
    WHERE av.attribute_id = a.id AND lower(av.attribute_value) = lower(s.value)
);

-- 6. PRODUCT ATTRIBUTE VALUES: one value per attribute per product.
WITH spec AS (
    SELECT slug, 'surface-area' AS attribute_id, surface_area AS value FROM tmp_seed_bio
    UNION ALL SELECT slug, 'material',      material     FROM tmp_seed_bio
    UNION ALL SELECT slug, 'ph-effect',     ph_effect    FROM tmp_seed_bio
    UNION ALL SELECT slug, 'purpose',      purpose      FROM tmp_seed_chemical
    UNION ALL SELECT slug, 'reusable',     reusable     FROM tmp_seed_chemical
    UNION ALL SELECT slug, 'service-life', service_life FROM tmp_seed_chemical
    UNION ALL SELECT slug, 'ph-effect',    ph_effect    FROM tmp_seed_chemical
    UNION ALL SELECT slug, 'filtration-level', filtration_level FROM tmp_seed_mechanical
    UNION ALL SELECT slug, 'reusable',         reusable         FROM tmp_seed_mechanical
    UNION ALL SELECT slug, 'service-life',     service_life     FROM tmp_seed_mechanical
    UNION ALL SELECT slug, 'ph-effect',        ph_effect        FROM tmp_seed_mechanical
)
INSERT INTO product_attribute_values
    (product_value_id, product_id, attribute_id, attribute_value_id)
SELECT p.product_id || '-' || s.attribute_id, p.id, a.id, av.id
FROM spec s
JOIN products p ON lower(p.product_slug) = lower(s.slug)
JOIN attributes a ON a.attribute_id = s.attribute_id
JOIN attribute_values av
  ON av.attribute_id = a.id AND lower(av.attribute_value) = lower(s.value)
ON CONFLICT (product_id, attribute_id) DO UPDATE
SET attribute_value_id = EXCLUDED.attribute_value_id;

DROP TABLE tmp_seed_bio;
DROP TABLE tmp_seed_chemical;
DROP TABLE tmp_seed_mechanical;
