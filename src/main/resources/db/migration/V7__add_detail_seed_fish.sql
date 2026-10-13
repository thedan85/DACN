-- =========================================================
-- V7: product_detail_description column + 19 more tetra products
--
-- 1. Adds products.product_detail_description (TEXT, plain text for now).
-- 2. Seeds 19 new products under category 'tetra':
--      18 single-variant tetras + devil-tetra with 3 Color variants
--      (Yellow / Blue / Red).
-- 3. Seeds the 8 fish specs (product_attribute_values) for the new products.
-- 4. Fills product_detail_description for ALL 25 fish (V6's 6 + the 19 new).
--
-- Depends on V5 (category 'tetra') and V6 (attributes, Color attribute).
-- Price/stock are PLACEHOLDERS (VND, stock 50). No thumbnails / media.
-- Tank-size rule used for filter consistency: 40 L for fish up to ~4 cm,
--   75 L for ~4.5-6 cm, 150 L for 7 cm+.
-- REVIEW BEFORE PUBLISHING: trade names that do not map to one clear species
--   (black-diamond, chocolate-neon, phoenix, golden-blue-eye-congo,
--   green-fire, rubra, inferno) use generic small-tetra values and generic
--   text; correct them once the exact species is confirmed.
-- =========================================================

-- ---------- 1. COLUMN ----------

ALTER TABLE products ADD COLUMN IF NOT EXISTS product_detail_description TEXT;

-- ---------- 2. PRODUCTS ----------
-- thumbnail/status/detail not overwritten on conflict.

INSERT INTO products (product_id, product_name, product_description, product_thumbnail, product_status, category_id) VALUES
    ('neon-tetra', 'Neon Tetra',
     'Classic schooling tetra with an iridescent blue stripe over a red lower body. A staple of planted community tanks.',
     NULL, 'ACTIVE', 'tetra'),
    ('black-neon-tetra', 'Black Neon Tetra',
     'Peaceful schooling tetra with a bold black stripe above a silver-white stripe. Hardy and a good fit for planted tanks.',
     NULL, 'ACTIVE', 'tetra'),
    ('cardinal-tetra', 'Cardinal Tetra',
     'Brilliant blue and red schooling tetra whose red band runs the full length of the body. Looks best in a large group in a planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('diamond-tetra', 'Diamond Tetra',
     'Larger schooling tetra with glittering scales and flowing fins. Prefers a roomy planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('black-diamond-tetra', 'Black Diamond Tetra',
     'Dark-bodied schooling tetra with a glittering sheen. Best kept in a group in a roomy planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('green-neon-tetra', 'Green Neon Tetra',
     'Smaller relative of the neon tetra with a green-blue iridescent stripe. Does best in a mature, planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('glowlight-tetra', 'Glowlight Tetra',
     'Peaceful schooling tetra with a glowing orange-gold line along its body. Hardy and well suited to community tanks.',
     NULL, 'ACTIVE', 'tetra'),
    ('chocolate-neon-tetra', 'Chocolate Neon Tetra',
     'Small schooling tetra with a dark body and a glowing stripe. Best kept in a group in a planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('phoenix-tetra', 'Phoenix Tetra',
     'Small, active schooling tetra with warm red-orange tones. Best kept in a group in a planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('white-fin-rosy-tetra', 'White Fin Rosy Tetra',
     'Rosy-colored schooling tetra with white-tipped fins. Peaceful and well suited to planted community tanks.',
     NULL, 'ACTIVE', 'tetra'),
    ('black-phantom-tetra', 'Black Phantom Tetra',
     'Hardy, peaceful schooling tetra with striking black markings and silver-grey tones.',
     NULL, 'ACTIVE', 'tetra'),
    ('pristella-tetra', 'Pristella Tetra',
     'Hardy schooling tetra with a translucent body and black, white and yellow-tipped fins. Easy to keep in community tanks.',
     NULL, 'ACTIVE', 'tetra'),
    ('golden-blue-eye-congo-tetra', 'Golden Blue Eye Congo Tetra',
     'Golden-toned congo tetra variety with striking blue eyes. Needs a spacious tank and calm tank mates.',
     NULL, 'ACTIVE', 'tetra'),
    ('green-fire-tetra', 'Green Fire Tetra',
     'Small schooling tetra with a metallic green shimmer and fiery highlights. Best kept in a planted tank in a group.',
     NULL, 'ACTIVE', 'tetra'),
    ('cochu-tetra', 'Cochu Tetra',
     'Small, peaceful, blue-hued tetra that looks best in a group in a planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('lemon-tetra', 'Lemon Tetra',
     'Peaceful schooling tetra with a glowing lemon-yellow body. Hardy and easy to keep.',
     NULL, 'ACTIVE', 'tetra'),
    ('rubra-tetra', 'Rubra Tetra',
     'Small schooling tetra with red coloration. Best kept in a group in a planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('inferno-tetra', 'Inferno Tetra',
     'Small schooling tetra with vivid fiery coloration. Best kept in a group in a planted tank.',
     NULL, 'ACTIVE', 'tetra'),
    ('devil-tetra', 'Devil Tetra',
     'Peaceful schooling tetra sold in several color forms. Far gentler than its name suggests.',
     NULL, 'ACTIVE', 'tetra')
ON CONFLICT (product_id) DO UPDATE SET
    product_name        = EXCLUDED.product_name,
    product_description = EXCLUDED.product_description,
    category_id         = EXCLUDED.category_id;

-- ---------- 3. VARIANTS ----------
-- price/stock not overwritten on conflict (they change at runtime).

INSERT INTO variants (variant_id, variant_sku, variant_price, variant_stock, product_id) VALUES
    ('neon-tetra-default',                 'FISH-NEON-TETRA',                 8000.00, 50, 'neon-tetra'),
    ('black-neon-tetra-default',           'FISH-BLACK-NEON-TETRA',          10000.00, 50, 'black-neon-tetra'),
    ('cardinal-tetra-default',             'FISH-CARDINAL-TETRA',            15000.00, 50, 'cardinal-tetra'),
    ('diamond-tetra-default',              'FISH-DIAMOND-TETRA',             20000.00, 50, 'diamond-tetra'),
    ('black-diamond-tetra-default',        'FISH-BLACK-DIAMOND-TETRA',       25000.00, 50, 'black-diamond-tetra'),
    ('green-neon-tetra-default',           'FISH-GREEN-NEON-TETRA',          12000.00, 50, 'green-neon-tetra'),
    ('glowlight-tetra-default',            'FISH-GLOWLIGHT-TETRA',            8000.00, 50, 'glowlight-tetra'),
    ('chocolate-neon-tetra-default',       'FISH-CHOCOLATE-NEON-TETRA',      15000.00, 50, 'chocolate-neon-tetra'),
    ('phoenix-tetra-default',              'FISH-PHOENIX-TETRA',             20000.00, 50, 'phoenix-tetra'),
    ('white-fin-rosy-tetra-default',       'FISH-WHITE-FIN-ROSY-TETRA',      10000.00, 50, 'white-fin-rosy-tetra'),
    ('black-phantom-tetra-default',        'FISH-BLACK-PHANTOM-TETRA',       10000.00, 50, 'black-phantom-tetra'),
    ('pristella-tetra-default',            'FISH-PRISTELLA-TETRA',            8000.00, 50, 'pristella-tetra'),
    ('golden-blue-eye-congo-tetra-default','FISH-GOLDEN-BLUE-EYE-CONGO-TETRA',80000.00, 50, 'golden-blue-eye-congo-tetra'),
    ('green-fire-tetra-default',           'FISH-GREEN-FIRE-TETRA',          15000.00, 50, 'green-fire-tetra'),
    ('cochu-tetra-default',                'FISH-COCHU-TETRA',               15000.00, 50, 'cochu-tetra'),
    ('lemon-tetra-default',                'FISH-LEMON-TETRA',               10000.00, 50, 'lemon-tetra'),
    ('rubra-tetra-default',                'FISH-RUBRA-TETRA',               15000.00, 50, 'rubra-tetra'),
    ('inferno-tetra-default',              'FISH-INFERNO-TETRA',             20000.00, 50, 'inferno-tetra'),
    ('devil-tetra-yellow',                 'FISH-DEVIL-TETRA-YELLOW',        30000.00, 50, 'devil-tetra'),
    ('devil-tetra-blue',                   'FISH-DEVIL-TETRA-BLUE',          35000.00, 50, 'devil-tetra'),
    ('devil-tetra-red',                    'FISH-DEVIL-TETRA-RED',           30000.00, 50, 'devil-tetra')
ON CONFLICT (variant_id) DO UPDATE SET
    variant_sku = EXCLUDED.variant_sku,
    product_id  = EXCLUDED.product_id;

-- ---------- 4. VARIANT ATTRIBUTE VALUES (Color for devil tetra) ----------

INSERT INTO attribute_values (attribute_value_id, attribute_id, attribute_value) VALUES
    ('color-yellow', 'color', 'Yellow'),
    ('color-blue',   'color', 'Blue'),
    ('color-red',    'color', 'Red')
ON CONFLICT (attribute_id, attribute_value_id) DO UPDATE SET
    attribute_value = EXCLUDED.attribute_value;

INSERT INTO variant_attribute_values (variant_value_id, variant_id, attribute_id, attribute_value_id) VALUES
    ('devil-tetra-yellow-color', 'devil-tetra-yellow', 'color', 'color-yellow'),
    ('devil-tetra-blue-color',   'devil-tetra-blue',   'color', 'color-blue'),
    ('devil-tetra-red-color',    'devil-tetra-red',    'color', 'color-red')
ON CONFLICT (variant_id, attribute_id) DO UPDATE SET
    attribute_value_id = EXCLUDED.attribute_value_id;

-- ---------- 5. PRODUCT ATTRIBUTE VALUES (fish specs) ----------
-- One row per product in "fish"; unpivoted into (product, attribute, value),
-- shared attribute_values derived from the values, then linked.

WITH fish (product_id, temperature, temperament, care_level, aquarium_level, min_tank, size, ph, diet) AS (VALUES
    ('neon-tetra',                  '20-26 °C', 'Peaceful', 'Easy',     'Middle', '40 L',  '3-4 cm',   '5.5-7.0', 'Omnivore'),
    ('black-neon-tetra',            '22-27 °C', 'Peaceful', 'Easy',     'Middle', '40 L',  '3.5-4 cm', '5.5-7.5', 'Omnivore'),
    ('cardinal-tetra',              '23-27 °C', 'Peaceful', 'Moderate', 'Middle', '75 L',  '4-5 cm',   '5.0-7.0', 'Omnivore'),
    ('diamond-tetra',               '23-28 °C', 'Peaceful', 'Easy',     'Middle', '75 L',  '5-6 cm',   '5.5-7.5', 'Omnivore'),
    ('black-diamond-tetra',         '23-28 °C', 'Peaceful', 'Easy',     'Middle', '75 L',  '5-6 cm',   '5.5-7.5', 'Omnivore'),
    ('green-neon-tetra',            '23-28 °C', 'Peaceful', 'Moderate', 'Middle', '40 L',  '2.5-3 cm', '5.0-7.0', 'Omnivore'),
    ('glowlight-tetra',             '23-28 °C', 'Peaceful', 'Easy',     'Middle', '40 L',  '4 cm',     '5.5-7.5', 'Omnivore'),
    ('chocolate-neon-tetra',        '22-27 °C', 'Peaceful', 'Easy',     'Middle', '40 L',  '3-4 cm',   '5.5-7.5', 'Omnivore'),
    ('phoenix-tetra',               '22-28 °C', 'Peaceful', 'Easy',     'Middle', '40 L',  '3-4 cm',   '6.0-7.5', 'Omnivore'),
    ('white-fin-rosy-tetra',        '22-27 °C', 'Peaceful', 'Easy',     'Middle', '75 L',  '4-5 cm',   '5.5-7.5', 'Omnivore'),
    ('black-phantom-tetra',         '22-27 °C', 'Peaceful', 'Easy',     'Middle', '75 L',  '4-5 cm',   '5.5-7.5', 'Omnivore'),
    ('pristella-tetra',             '23-28 °C', 'Peaceful', 'Easy',     'Middle', '75 L',  '4-5 cm',   '6.0-7.5', 'Omnivore'),
    ('golden-blue-eye-congo-tetra', '22-28 °C', 'Peaceful', 'Moderate', 'Middle', '150 L', '7-9 cm',   '6.0-7.5', 'Omnivore'),
    ('green-fire-tetra',            '22-27 °C', 'Peaceful', 'Easy',     'Middle', '40 L',  '3-4 cm',   '6.0-7.5', 'Omnivore'),
    ('cochu-tetra',                 '23-28 °C', 'Peaceful', 'Easy',     'Middle', '40 L',  '3 cm',     '5.5-7.0', 'Omnivore'),
    ('lemon-tetra',                 '21-27 °C', 'Peaceful', 'Easy',     'Middle', '75 L',  '4-5 cm',   '6.0-7.5', 'Omnivore'),
    ('rubra-tetra',                 '22-28 °C', 'Peaceful', 'Easy',     'Middle', '75 L',  '4-5 cm',   '6.0-7.5', 'Omnivore'),
    ('inferno-tetra',               '22-28 °C', 'Peaceful', 'Easy',     'Middle', '40 L',  '3-4 cm',   '6.0-7.5', 'Omnivore'),
    ('devil-tetra',                 '22-28 °C', 'Peaceful', 'Easy',     'Middle', '75 L',  '3-4 cm',   '6.5-7.5', 'Omnivore')
),
spec (product_id, attribute_id, attribute_value) AS (
    SELECT f.product_id, x.attribute_id, x.attribute_value
    FROM fish f
    CROSS JOIN LATERAL (VALUES
        ('temperature',       f.temperature),
        ('temperament',       f.temperament),
        ('care-level',        f.care_level),
        ('aquarium-level',    f.aquarium_level),
        ('minimum-tank-size', f.min_tank),
        ('size',              f.size),
        ('ph',                f.ph),
        ('diet',              f.diet)
    ) AS x (attribute_id, attribute_value)
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

-- ---------- 6. DETAIL TEXT (all 25 fish, plain text) ----------
-- Dollar-quoted ($d$...$d$) so line breaks and apostrophes are kept as-is.

UPDATE products p
SET product_detail_description = d.detail
FROM (VALUES
-- ===== V6 fish =====
('ember-tetra', $d$Ember Tetra Care Guide

The Ember Tetra is a tiny, glowing orange-red schooling fish that suits nano tanks and planted aquariums.

Tank Setup: Heavily planted tanks with floating plants and soft lighting bring out the deepest color. Keep the water clean and stable.
Feeding Tips: Offer small flakes, micro pellets and occasional frozen foods such as daphnia or baby brine shrimp. Their mouths are very small, so keep food fine.
Community & Behavior: Peaceful and shy. Keep 8 or more together and choose similarly small, calm tank mates.$d$),

('red-cherry-tetra', $d$Red Cherry Tetra Care Guide

The Red Cherry Tetra is a peaceful schooling fish with deep red coloration that stands out against green plants and a dark substrate.

Tank Setup: A planted tank with dark substrate, driftwood and subdued lighting shows off the red color. Leave open space in the middle for the school to swim.
Feeding Tips: Feed small flakes or micro pellets, with occasional frozen or live foods to boost color.
Community & Behavior: Peaceful and social. Keep a group of 8 or more with other small, calm species.$d$),

('raccoon-tetra', $d$Raccoon Tetra Care Guide

The Raccoon Tetra is a lively, peaceful schooling fish with a rosy-gold body and bold black markings.

Tank Setup: Use a planted tank with some open swimming space and moderate lighting. A mature, well-filtered tank keeps the fish healthy.
Feeding Tips: Accepts flakes, small pellets and occasional frozen or live foods. Feed small amounts once or twice a day.
Community & Behavior: Active and peaceful. Keep at least 6 together; they mix well with other small to medium community fish.$d$),

('congo-tetra', $d$Congo Tetra Care Guide

The Congo Tetra is a large, shimmering African tetra with long, flowing fins. Males develop the most impressive colors and finnage.

Tank Setup: Needs a spacious tank with room to swim, soft diffused light, floating plants and some dark substrate. Regular water changes help keep the fins and color in top condition.
Feeding Tips: Omnivores that accept quality flakes and pellets, plus frozen or live foods such as daphnia and bloodworms. Add some vegetable matter.
Community & Behavior: Peaceful, but best kept in a school of 6 or more. Avoid fin-nipping tank mates, since the long fins are easy targets.$d$),

('dario-dario', $d$Scarlet Badis Care Guide

The Scarlet Badis (Dario dario) is a highly rewarding micro predator. To see them display their brightest scarlet colors and proud territorial behavior, setting up their environment correctly is key.

Planted Tank Setup: Densely planted nano-tanks with plenty of Java Moss, Anubias, and hiding places replicate their slow-flowing native waters in India.
Feeding Tips: They are micro-predators and can be finicky eaters. They excel when fed small live or high-quality frozen micro-foods like baby brine shrimp, daphnia, or microworms.
Community & Behavior: Males can defend small territories fiercely from rival males. We recommend keeping a single male with multiple females or a single specimen in a peaceful community setup.$d$),

('badis-badis', $d$Blue Badis Care Guide

The Blue Badis (Badis badis) is a small micro predator with iridescent blue colors and plenty of personality.

Tank Setup: Use a densely planted tank with caves, rockwork or driftwood for hiding, and gentle water flow. A mature tank gives them natural food to graze on.
Feeding Tips: Prefers small live or frozen foods such as baby brine shrimp, daphnia and bloodworms. Some individuals can be trained onto small pellets.
Community & Behavior: Males can be territorial toward other males. Keep one male with several females, and avoid boisterous or much larger tank mates.$d$),

-- ===== New tetras =====
('neon-tetra', $d$Neon Tetra Care Guide

The Neon Tetra is a classic schooling fish with an iridescent blue stripe and a red lower body, and a staple of planted community tanks.

Tank Setup: Prefers a mature, planted tank with subdued lighting and stable water. Avoid adding them to a brand-new tank.
Feeding Tips: Feed small flakes or micro pellets, with occasional frozen foods such as daphnia or baby brine shrimp.
Community & Behavior: Peaceful. Keep 8 or more together and avoid large or aggressive tank mates that may see them as food.$d$),

('black-neon-tetra', $d$Black Neon Tetra Care Guide

The Black Neon Tetra has a bold black stripe above a silver-white stripe. It is hardy and a good choice for planted tanks.

Tank Setup: A planted tank with dark substrate and subdued lighting suits them well. Provide open swimming space in the middle of the tank.
Feeding Tips: Accepts flakes, micro pellets and small frozen or live foods.
Community & Behavior: Peaceful and social. Keep 6 or more together with other small, calm species.$d$),

('cardinal-tetra', $d$Cardinal Tetra Care Guide

The Cardinal Tetra has a brilliant blue stripe and a red band that runs the full length of the body. It looks best in a large school in a planted tank.

Tank Setup: Prefers a mature tank with soft, slightly acidic water, dim lighting, floating plants and driftwood. Avoid sudden water changes.
Feeding Tips: Small flakes, micro pellets and occasional frozen or live foods such as daphnia.
Community & Behavior: Peaceful. Keep at least 8 together and choose calm tank mates that are not much larger than them.$d$),

('diamond-tetra', $d$Diamond Tetra Care Guide

The Diamond Tetra is a larger schooling fish with glittering, iridescent scales and flowing fins on mature males.

Tank Setup: Give them a roomy planted tank with open swimming space and soft lighting. A dark substrate shows off the shimmer.
Feeding Tips: Omnivores that accept flakes, pellets and frozen or live foods. Some vegetable matter is welcome.
Community & Behavior: Peaceful and active. Keep a group of 6 or more with other calm community fish.$d$),

('black-diamond-tetra', $d$Black Diamond Tetra Care Guide

The Black Diamond Tetra is a dark-bodied schooling fish with a glittering sheen, best kept in a group in a roomy planted tank.

Tank Setup: Give them a roomy planted tank with open swimming space and soft lighting. A dark substrate shows off the shimmer.
Feeding Tips: Omnivores that accept flakes, pellets and frozen or live foods. Some vegetable matter is welcome.
Community & Behavior: Peaceful and active. Keep a group of 6 or more with other calm community fish.$d$),

('green-neon-tetra', $d$Green Neon Tetra Care Guide

The Green Neon Tetra is a smaller relative of the neon with a green-blue iridescent stripe. It does best in a mature planted tank.

Tank Setup: Prefers soft, slightly acidic water, subdued lighting and lots of plants. Keep the tank stable and avoid sudden changes.
Feeding Tips: They have very small mouths, so feed fine flakes, micro pellets and tiny frozen foods like baby brine shrimp.
Community & Behavior: Peaceful. Keep 8 or more with other small, calm fish.$d$),

('glowlight-tetra', $d$Glowlight Tetra Care Guide

The Glowlight Tetra has a glowing orange-gold line along its body and is hardy enough for most community tanks.

Tank Setup: A planted tank with subdued lighting and floating plants works well. Provide open swimming space.
Feeding Tips: Accepts flakes, micro pellets and occasional frozen or live foods.
Community & Behavior: Peaceful. Keep 6 or more together with other small, calm species.$d$),

('chocolate-neon-tetra', $d$Chocolate Neon Tetra Care Guide

The Chocolate Neon Tetra is a small schooling fish with a dark body and a glowing stripe. Best kept in a group in a planted tank.

Tank Setup: A planted tank with dark substrate and subdued lighting. Keep the water clean and stable.
Feeding Tips: Accepts small flakes, micro pellets and occasional frozen or live foods.
Community & Behavior: Peaceful. Keep 6 or more together with other small, calm species.$d$),

('phoenix-tetra', $d$Phoenix Tetra Care Guide

The Phoenix Tetra is a small, active schooling fish with warm red-orange tones, best kept in a group in a planted tank.

Tank Setup: A planted tank with open swimming space and subdued lighting. Keep the water clean and stable.
Feeding Tips: Accepts small flakes, micro pellets and occasional frozen or live foods.
Community & Behavior: Peaceful. Keep 6 or more together with other small, calm species.$d$),

('white-fin-rosy-tetra', $d$White Fin Rosy Tetra Care Guide

The White Fin Rosy Tetra has a rosy body and white-tipped fins. It is peaceful and well suited to planted community tanks.

Tank Setup: Keep in a planted tank with subdued lighting and open swimming space.
Feeding Tips: Accepts flakes, small pellets and occasional frozen or live foods.
Community & Behavior: Peaceful. Keep 6 or more together with other small to medium community fish.$d$),

('black-phantom-tetra', $d$Black Phantom Tetra Care Guide

The Black Phantom Tetra is a hardy schooling fish with striking black markings and silver-grey tones.

Tank Setup: A planted tank with subdued lighting and open swimming space. Hardy and tolerant of a range of water conditions.
Feeding Tips: Accepts flakes, small pellets and occasional frozen or live foods.
Community & Behavior: Peaceful and sociable. Keep 6 or more together; males may display to each other but rarely cause harm.$d$),

('pristella-tetra', $d$Pristella Tetra Care Guide

The Pristella Tetra has a translucent body with black, white and yellow-tipped fins. It is hardy and easy to keep in community tanks.

Tank Setup: A planted tank with open swimming space and moderate lighting. Tolerant of a range of water conditions.
Feeding Tips: Accepts flakes, small pellets and occasional frozen or live foods.
Community & Behavior: Peaceful and active. Keep 6 or more together with other small to medium community fish.$d$),

('golden-blue-eye-congo-tetra', $d$Golden Blue Eye Congo Tetra Care Guide

The Golden Blue Eye Congo Tetra is a golden-toned congo tetra variety with striking blue eyes. It needs a spacious tank and calm tank mates.

Tank Setup: Needs a spacious tank with room to swim, soft diffused light, floating plants and some dark substrate. Regular water changes help keep the fins and color in top condition.
Feeding Tips: Omnivores that accept quality flakes and pellets, plus frozen or live foods such as daphnia and bloodworms. Add some vegetable matter.
Community & Behavior: Peaceful, but best kept in a school of 6 or more. Avoid fin-nipping tank mates, since the long fins are easy targets.$d$),

('green-fire-tetra', $d$Green Fire Tetra Care Guide

The Green Fire Tetra is a small schooling fish with a metallic green shimmer and fiery highlights.

Tank Setup: A planted tank with open swimming space and subdued lighting. Keep the water clean and stable.
Feeding Tips: Accepts small flakes, micro pellets and occasional frozen or live foods.
Community & Behavior: Peaceful. Keep 6 or more together with other small, calm species.$d$),

('cochu-tetra', $d$Cochu Tetra Care Guide

The Cochu Tetra is a small, peaceful, blue-hued tetra that looks best in a group in a planted tank.

Tank Setup: A dark substrate, plenty of plants and subdued lighting show off the blue sheen. Keep the water clean and stable.
Feeding Tips: Feed small flakes or micro pellets, with occasional frozen foods such as daphnia or baby brine shrimp.
Community & Behavior: Peaceful. Keep 6 or more together with other small, calm species.$d$),

('lemon-tetra', $d$Lemon Tetra Care Guide

The Lemon Tetra has a glowing lemon-yellow body and is hardy and easy to keep.

Tank Setup: A planted tank with open swimming space. A dark substrate and subdued lighting bring out the yellow.
Feeding Tips: Accepts flakes, small pellets and occasional frozen or live foods.
Community & Behavior: Peaceful and active. Keep 6 or more together with other small to medium community fish.$d$),

('rubra-tetra', $d$Rubra Tetra Care Guide

The Rubra Tetra is a small schooling fish with red coloration, best kept in a group in a planted tank.

Tank Setup: A planted tank with open swimming space and subdued lighting. Keep the water clean and stable.
Feeding Tips: Accepts small flakes, micro pellets and occasional frozen or live foods.
Community & Behavior: Peaceful. Keep 6 or more together with other small, calm species.$d$),

('inferno-tetra', $d$Inferno Tetra Care Guide

The Inferno Tetra is a small schooling fish with vivid fiery coloration, best kept in a group in a planted tank.

Tank Setup: A planted tank with open swimming space and subdued lighting. Keep the water clean and stable.
Feeding Tips: Accepts small flakes, micro pellets and occasional frozen or live foods.
Community & Behavior: Peaceful. Keep 6 or more together with other small, calm species.$d$),

('devil-tetra', $d$Devil Tetra Care Guide

The Devil Tetra comes in several color forms (yellow, blue and red) and is far gentler than its name suggests.

Tank Setup: Provide a planted tank with open swimming space, gentle water flow and subdued lighting.
Feeding Tips: Accepts flakes, small pellets and occasional frozen or live foods.
Community & Behavior: Peaceful and lively. Keep 6 or more together; males may display to each other but rarely harm other fish.$d$)
) AS d (product_id, detail)
WHERE p.product_id = d.product_id;