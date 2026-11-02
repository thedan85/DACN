-- =========================================================
-- V27: Seed the Cloudinary "Plant" folder catalog
--
-- Source: Cloudinary Plant/* (21 images)
--   Plant/carpet            1  -> foreground-carpet
--   Plant/epiphytes         9  -> epiphyte (5 anubias + 4 bucephalandra)
--   Plant/floating          5  -> floating
--   Plant/Moss              6  -> moss
--   Plant/epiphytes/fern    0  -> skipped (empty folder)
--
-- One default variant and one product-scoped media row per plant.
-- Plant specs: care level, CO2 demand, lighting, nutrient demand,
--   temperature, color.
-- Price/stock are PLACEHOLDERS (VND, stock 50) like V6/V18.
-- Idempotent: product_slug/variant_id/media_url guards.
-- =========================================================

DROP TABLE IF EXISTS tmp_seed_plant;

CREATE TEMP TABLE tmp_seed_plant (
    slug            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    category_id     TEXT NOT NULL,
    description     TEXT NOT NULL,
    detail          TEXT NOT NULL,
    price           NUMERIC(10,2) NOT NULL,
    care_level      TEXT NOT NULL,
    co2_demand      TEXT NOT NULL,
    light_demand    TEXT NOT NULL,
    nutrient_demand TEXT NOT NULL,
    temperature     TEXT NOT NULL,
    color           TEXT NOT NULL,
    image_url       TEXT NOT NULL
);

INSERT INTO tmp_seed_plant
    (slug, name, category_id, description, detail, price,
     care_level, co2_demand, light_demand, nutrient_demand, temperature, color, image_url)
VALUES
('eleocharis-parvula-mini', 'Eleocharis Parvula Mini', 'foreground-carpet',
 'Tiny dwarf hairgrass that forms a dense, low carpet. Needs strong light and CO2 to spread into a full lawn.',
 $d$Eleocharis Parvula Mini Care Guide

Tiny dwarf hairgrass that forms a dense, low carpet. Needs strong light and CO2 to spread into a full lawn.

Tank Setup: Fine aqua soil, high lighting and steady CO2 injection. Keep the water cool and clean to avoid algae on the carpet.
Planting Tips: Divide the pot into small clumps and plant them 2-3 cm apart. Trim the tops after planting to encourage runners.
Placement: Foreground carpet. Give it two to three weeks to root before adding bottom dwellers.$d$,
 45000.00, 'Moderate', 'Medium', 'High', 'Medium', '18-26 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793278917/eleocharis_parvula_mini.jpg'),

('anubias-chili', 'Anubias Chili', 'epiphyte',
 'Very small anubias with narrow, chili-shaped leaves. Slow growing and ideal for nano tanks and stone work.',
 $d$Anubias Chili Care Guide

Very small anubias with narrow, chili-shaped leaves. Slow growing and ideal for nano tanks and stone work.

Tank Setup: Low to medium light with stable water. CO2 is optional and mainly speeds up growth.
Planting Tips: Tie or glue the rhizome to rock or driftwood. Never bury the rhizome in the substrate.
Placement: Foreground or midground on hardscape. Avoid placing it in deep shade where leaves collect debris.$d$,
 150000.00, 'Easy', 'Low', 'Low', 'Low', '20-28 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791983595/anubias_chili.webp'),

('anubias-pangolino', 'Anubias Pangolino', 'epiphyte',
 'Miniature anubias with rounded, overlapping leaves that form a tight rosette. A collector plant for nano aquascapes.',
 $d$Anubias Pangolino Care Guide

Miniature anubias with rounded, overlapping leaves that form a tight rosette. A collector plant for nano aquascapes.

Tank Setup: Low light and clean, well-oxygenated water. It tolerates low CO2 but grows faster with a little.
Planting Tips: Attach the rhizome to small stones or wood with thread or gel glue. Keep the rhizome exposed.
Placement: Foreground detail on hardscape. It is slow, so place it where it will not be shaded out.$d$,
 180000.00, 'Easy', 'Low', 'Low', 'Low', '20-28 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791983382/anubias_pangolino.webp'),

('anubias-nana-pinto', 'Anubias Nana Pinto', 'epiphyte',
 'Variegated anubias nana with white and green leaves. Slower than the plain form and best kept stable.',
 $d$Anubias Nana Pinto Care Guide

Variegated anubias nana with white and green leaves. Slower than the plain form and best kept stable.

Tank Setup: Low to medium light. Too much light can burn the white sections, so aim for shade under taller plants.
Planting Tips: Attach the rhizome to wood or rock. Do not bury it, and do not move it once it starts to root.
Placement: Midground accent on hardscape. Trim any leaves that revert to all green if you want to keep the pattern.$d$,
 220000.00, 'Moderate', 'Low', 'Low', 'Low', '20-28 °C', 'Green and white',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791981051/anubias_nana_pinto.webp'),

('anubias-nana-petite', 'Anubias Nana Petite', 'epiphyte',
 'The smallest common anubias, with tiny dark green leaves. Perfect for nano tanks and detailed hardscape.',
 $d$Anubias Nana Petite Care Guide

The smallest common anubias, with tiny dark green leaves. Perfect for nano tanks and detailed hardscape.

Tank Setup: Low light and steady parameters. It grows in almost any tank but dislikes being buried.
Planting Tips: Tie the rhizome to stone or driftwood and let it creep naturally over time.
Placement: Foreground or midground on hardscape. Keep it clear of fast-growing stems that would shade it.$d$,
 120000.00, 'Easy', 'Low', 'Low', 'Low', '20-28 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791980988/anubias_nana_petite.webp'),

('anubias-barteri-nana', 'Anubias Barteri Nana', 'epiphyte',
 'Classic broad-leaf anubias. Hardy, slow growing and one of the best plants for beginners.',
 $d$Anubias Barteri Nana Care Guide

Classic broad-leaf anubias. Hardy, slow growing and one of the best plants for beginners.

Tank Setup: Low to medium light with stable water. It tolerates a wide range of parameters and low CO2.
Planting Tips: Attach the rhizome to rock or wood. The rhizome must stay above the substrate or it will rot.
Placement: Midground on hardscape. Its broad leaves are a natural shelter for shrimp and small fish.$d$,
 90000.00, 'Easy', 'Low', 'Low', 'Low', '20-28 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791980850/anubias_barteri_nana.png'),

('bucephalandra-black-angel', 'Bucephalandra Black Angel', 'epiphyte',
 'Dark, narrow-leaf bucephalandra with a deep green to black sheen. A striking hardscape plant.',
 $d$Bucephalandra Black Angel Care Guide

Dark, narrow-leaf bucephalandra with a deep green to black sheen. A striking hardscape plant.

Tank Setup: Low to medium light with cool, clean water. Stable parameters matter more than strong light.
Planting Tips: Glue or tie the rhizome to rock and wood. Keep the rhizome exposed and the flow gentle.
Placement: Midground on dark stone. The dark leaves show best against pale aqua soil or light rock.$d$,
 200000.00, 'Moderate', 'Low', 'Low', 'Low', '20-26 °C', 'Dark green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791984229/bucep_black_angel.webp'),

('bucephalandra-ghost', 'Bucephalandra Ghost', 'epiphyte',
 'Pale green bucephalandra with a soft, ghostly tone. Slow growing and ideal for shaded hardscape.',
 $d$Bucephalandra Ghost Care Guide

Pale green bucephalandra with a soft, ghostly tone. Slow growing and ideal for shaded hardscape.

Tank Setup: Low light and stable, cool water. Avoid sudden changes in temperature or hardness.
Planting Tips: Attach the rhizome to wood or stone. Do not bury it, and leave room for the colony to spread.
Placement: Midground or foreground on hardscape. It pairs well with dark substrate and moss.$d$,
 160000.00, 'Moderate', 'Low', 'Low', 'Low', '20-26 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791983794/bucep_ghost.webp'),

('bucephalandra-needle', 'Bucephalandra Needle', 'epiphyte',
 'Fine, needle-shaped bucephalandra leaves. Compact and elegant on small stones and wood.',
 $d$Bucephalandra Needle Care Guide

Fine, needle-shaped bucephalandra leaves. Compact and elegant on small stones and wood.

Tank Setup: Low to medium light in cool, clean water. Good flow keeps the fine leaves free of debris.
Planting Tips: Glue the rhizome to hardscape. Keep it off the substrate to prevent rot.
Placement: Foreground or midground detail. Trim old leaves to keep the clump dense.$d$,
 150000.00, 'Moderate', 'Low', 'Low', 'Low', '20-26 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791983914/bucep_needle.webp'),

('bucephalandra-neo-crimson', 'Bucephalandra Neo Crimson', 'epiphyte',
 'Red-toned bucephalandra that colours up under moderate light. A popular collector plant.',
 $d$Bucephalandra Neo Crimson Care Guide

Red-toned bucephalandra that colours up under moderate light. A popular collector plant.

Tank Setup: Medium light with CO2 helps the red tones develop. Keep the water cool and stable.
Planting Tips: Attach the rhizome to rock or wood. Avoid burying it and give it gentle flow.
Placement: Midground accent. The red leaves contrast well with green moss and pale stone.$d$,
 180000.00, 'Moderate', 'Low', 'Low', 'Low', '20-26 °C', 'Green and red',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791984322/bucep_neo_crimson.webp'),

('salvinia-minima', 'Salvinia Minima', 'floating',
 'Small floating fern with paired, velvety leaves. Fast growing and excellent at soaking up nutrients.',
 $d$Salvinia Minima Care Guide

Small floating fern with paired, velvety leaves. Fast growing and excellent at soaking up nutrients.

Tank Setup: Medium light and low flow. Keep it away from filter outlets and strong surface movement.
Planting Tips: Float it freely; it needs no substrate. Thin out the mat regularly so light still reaches below.
Placement: Water surface. Do not cover more than a third of the surface if the tank has submerged plants.$d$,
 25000.00, 'Easy', 'Low', 'Medium', 'Low', '18-28 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793277343/salvinia_minima.webp'),

('duckweed', 'Duckweed', 'floating',
 'Tiny floating plant that spreads very fast and removes nutrients. Best for dedicated grow-out tanks.',
 $d$Duckweed Care Guide

Tiny floating plant that spreads very fast and removes nutrients. Best for dedicated grow-out tanks.

Tank Setup: Any light level and low flow. It is almost impossible to kill once established.
Planting Tips: Float it freely and skim the excess every week. Keep it out of tanks where you cannot control it.
Placement: Water surface. It blocks light quickly, so use it carefully with carpeting plants.$d$,
 20000.00, 'Easy', 'Low', 'Medium', 'Low', '18-28 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793277230/duck_weed.jpg'),

('amazon-frogbit', 'Amazon Frogbit', 'floating',
 'Large floating plant with rounded leaves and long trailing roots. A natural cover for surface dwellers.',
 $d$Amazon Frogbit Care Guide

Large floating plant with rounded leaves and long trailing roots. A natural cover for surface dwellers.

Tank Setup: Medium light and low flow. Its long roots hang into the water and shelter small fish.
Planting Tips: Float it freely and remove old leaves. Leave space between plants so they do not crowd each other.
Placement: Water surface. Good for tanks with fish that need shade, but it will shade submerged plants below.$d$,
 30000.00, 'Easy', 'Low', 'Medium', 'Medium', '20-28 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793277080/amazon_frogbit.webp'),

('water-lettuce', 'Water Lettuce', 'floating',
 'Rosette-shaped floating plant with soft, ribbed leaves. Nice surface cover for roomy tanks.',
 $d$Water Lettuce Care Guide

Rosette-shaped floating plant with soft, ribbed leaves. Nice surface cover for roomy tanks.

Tank Setup: Medium light and calm water. Keep the leaves dry and away from condensation drips.
Planting Tips: Float it freely and thin out runners. Avoid wetting the leaves for long periods.
Placement: Water surface. A good choice for open-top tanks with gentle flow.$d$,
 35000.00, 'Moderate', 'Low', 'Medium', 'Medium', '20-28 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793276919/water_lettuce.webp'),

('red-root-floaters', 'Red Root Floaters', 'floating',
 'Compact floating plant with green leaves and red roots. A colourful surface accent for nano tanks.',
 $d$Red Root Floaters Care Guide

Compact floating plant with green leaves and red roots. A colourful surface accent for nano tanks.

Tank Setup: Medium light helps the red roots stay bright. Keep flow low and the surface humid.
Planting Tips: Float small clusters and let them join up. Thin the mat so light reaches the water below.
Placement: Water surface. They look best in shallow, still tanks with bright overhead light.$d$,
 40000.00, 'Moderate', 'Low', 'Medium', 'Medium', '20-28 °C', 'Green and red',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1793276901/red_root_floaters.webp'),

('marimo-moss', 'Marimo Moss', 'moss',
 'Round, velvety green moss balls. Slow growing and decorative on sand or stone.',
 $d$Marimo Moss Care Guide

Round, velvety green moss balls. Slow growing and decorative on sand or stone.

Tank Setup: Low light and cool water. Avoid high temperatures and strong light, which can brown the surface.
Planting Tips: Place the ball directly on the substrate or rotate it weekly so it keeps its shape.
Placement: Foreground accent. Shrimp love to graze on the surface of the ball.$d$,
 60000.00, 'Easy', 'Low', 'Low', 'Low', '15-25 °C', 'Dark green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791980671/marimo_moss.jpg'),

('bogor-moss', 'Bogor Moss', 'moss',
 'Fine, soft moss from Bogor with a dense, fern-like growth. Great for covering stone and wood.',
 $d$Bogor Moss Care Guide

Fine, soft moss from Bogor with a dense, fern-like growth. Great for covering stone and wood.

Tank Setup: Low to medium light and moderate flow. It grows well without CO2.
Planting Tips: Tie or glue small tufts to hardscape. Keep it trimmed so the inner layers get light.
Placement: Foreground and midground on hardscape. Good for shrimp tanks and nano aquascapes.$d$,
 40000.00, 'Easy', 'Low', 'Medium', 'Low', '20-28 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791980517/bogor_moss.webp'),

('weeping-moss', 'Weeping Moss', 'moss',
 'Moss with downward-curving tips that hang like a weeping willow. Ideal for wood and overhangs.',
 $d$Weeping Moss Care Guide

Moss with downward-curving tips that hang like a weeping willow. Ideal for wood and overhangs.

Tank Setup: Medium light and gentle flow. The tips droop best when the moss is grown on a raised branch.
Planting Tips: Tie or glue tufts to driftwood and let them grow outward.
Placement: Midground on branches. Trim the underside to keep the weeping shape clean.$d$,
 45000.00, 'Easy', 'Low', 'Medium', 'Low', '18-26 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791980430/weeping_moss.webp'),

('christmas-moss', 'Christmas Moss', 'moss',
 'Moss with triangular fronds that resemble fir branches. A classic aquascaping moss.',
 $d$Christmas Moss Care Guide

Moss with triangular fronds that resemble fir branches. A classic aquascaping moss.

Tank Setup: Medium light and moderate flow. CO2 is optional but improves density.
Planting Tips: Attach small clumps to wood or stone and trim regularly to keep the shape.
Placement: Midground on hardscape. The layered fronds look best when viewed from the front.$d$,
 45000.00, 'Easy', 'Low', 'Medium', 'Low', '18-26 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791979995/christmas_moss.webp'),

('flame-moss', 'Flame Moss', 'moss',
 'Moss that grows in twisting, flame-like strands. Best on vertical wood and stone.',
 $d$Flame Moss Care Guide

Moss that grows in twisting, flame-like strands. Best on vertical wood and stone.

Tank Setup: Medium light and steady flow. The flame shape develops when the moss grows upward.
Planting Tips: Glue or tie tufts to vertical hardscape and trim the sides to keep it compact.
Placement: Midground or background on wood. It makes a soft green column in a nano tank.$d$,
 50000.00, 'Easy', 'Low', 'Medium', 'Low', '18-26 °C', 'Dark green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791979939/flame_moss.webp'),

('taiwan-moss', 'Taiwan Moss', 'moss',
 'Fine-textured moss with a soft, feathery look. Fast to attach and easy to grow.',
 $d$Taiwan Moss Care Guide

Fine-textured moss with a soft, feathery look. Fast to attach and easy to grow.

Tank Setup: Medium light and moderate flow. It adapts to low light but grows looser.
Planting Tips: Tie or glue small tufts to rock and wood. Trim the top to encourage branching.
Placement: Foreground or midground on hardscape. A reliable moss for shrimp tanks and breeding boxes.$d$,
 40000.00, 'Easy', 'Low', 'Medium', 'Low', '18-26 °C', 'Green',
 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791979886/taiwan_moss.webp');

-- 1. ATTRIBUTES: plant-only specs not covered by the fish seed.
INSERT INTO attributes (attribute_id, attribute_name) VALUES
    ('co2-demand',      'CO2 demand'),
    ('light-demand',    'Lighting'),
    ('nutrient-demand', 'Nutrient')
ON CONFLICT (attribute_id) DO UPDATE SET
    attribute_name = EXCLUDED.attribute_name;

-- 2. PRODUCTS: temporary business id, rekeyed to PRD_<id> after insert.
INSERT INTO products
    (product_id, product_name, product_description, product_detail_description,
     product_status, category_id, product_slug)
SELECT 'seed:' || s.slug, s.name, s.description, s.detail,
       'ACTIVE', c.id, s.slug
FROM tmp_seed_plant s
JOIN categories c ON c.category_id = s.category_id
WHERE NOT EXISTS (
    SELECT 1 FROM products p WHERE lower(p.product_slug) = lower(s.slug)
);

UPDATE products
SET product_id = 'PRD_' || id
WHERE product_id LIKE 'seed:%';

-- 3. VARIANTS: one default variant per new product.
INSERT INTO variants (variant_id, variant_sku, variant_price, variant_stock, product_id)
SELECT s.slug || '-default', 'PLANT-' || upper(s.slug), s.price, 50, p.id
FROM tmp_seed_plant s
JOIN products p ON lower(p.product_slug) = lower(s.slug)
WHERE NOT EXISTS (
    SELECT 1 FROM variants v WHERE lower(v.variant_id) = lower(s.slug || '-default')
);

-- 4. MEDIA: one product-scoped image per new product, then thumbnail FK.
INSERT INTO media (media_id, media_url, product_id, is_deleted)
SELECT 'MED_' || s.slug, s.image_url, p.id, FALSE
FROM tmp_seed_plant s
JOIN products p ON lower(p.product_slug) = lower(s.slug)
WHERE NOT EXISTS (
    SELECT 1 FROM media m WHERE m.product_id = p.id AND m.media_url = s.image_url
);

UPDATE products p
SET thumbnail_media_id = (
    SELECT min(m.id) FROM media m WHERE m.product_id = p.id
)
WHERE p.thumbnail_media_id IS NULL
  AND EXISTS (
      SELECT 1 FROM tmp_seed_plant s WHERE lower(s.slug) = lower(p.product_slug)
  );

-- 5. ATTRIBUTE VALUES: shared (attribute, value) pairs for the 6 plant specs.
WITH spec AS (
    SELECT slug, 'care-level'      AS attribute_id, care_level      AS value FROM tmp_seed_plant
    UNION ALL SELECT slug, 'co2-demand',      co2_demand      FROM tmp_seed_plant
    UNION ALL SELECT slug, 'light-demand',    light_demand    FROM tmp_seed_plant
    UNION ALL SELECT slug, 'nutrient-demand', nutrient_demand FROM tmp_seed_plant
    UNION ALL SELECT slug, 'temperature',     temperature     FROM tmp_seed_plant
    UNION ALL SELECT slug, 'color',           color           FROM tmp_seed_plant
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

-- 6. PRODUCT ATTRIBUTE VALUES: link each new product to its spec values.
WITH spec AS (
    SELECT slug, 'care-level'      AS attribute_id, care_level      AS value FROM tmp_seed_plant
    UNION ALL SELECT slug, 'co2-demand',      co2_demand      FROM tmp_seed_plant
    UNION ALL SELECT slug, 'light-demand',    light_demand    FROM tmp_seed_plant
    UNION ALL SELECT slug, 'nutrient-demand', nutrient_demand FROM tmp_seed_plant
    UNION ALL SELECT slug, 'temperature',     temperature     FROM tmp_seed_plant
    UNION ALL SELECT slug, 'color',           color           FROM tmp_seed_plant
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

DROP TABLE tmp_seed_plant;
