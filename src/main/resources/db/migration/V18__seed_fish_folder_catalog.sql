-- =========================================================
-- V18: Seed the remaining fish from the Cloudinary "Fish" folder
--
-- Source: Cloudinary folder Fish/* (99 images). 27 images already
--   back the 25 seeded products; the 71 unmapped species are added
--   here with one default variant each, a product-scoped media row,
--   the 8 fish specs and a care-guide detail text.
-- albino_congo is not a new product: it is attached to the existing
--   congo-tetra-albino variant.
--
-- All categories used here already exist from V5.
-- Price/stock are PLACEHOLDERS (VND, stock 50) like V6/V7.
-- Idempotent: product_slug/media_url/customer+variant guards.
-- =========================================================

DROP TABLE IF EXISTS tmp_seed_fish;

CREATE TEMP TABLE tmp_seed_fish (
    slug            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    category_id     TEXT NOT NULL,
    description     TEXT NOT NULL,
    detail          TEXT NOT NULL,
    price           NUMERIC(10,2) NOT NULL,
    size            TEXT NOT NULL,
    temperament     TEXT NOT NULL,
    care_level      TEXT NOT NULL,
    temperature     TEXT NOT NULL,
    aquarium_level  TEXT NOT NULL,
    ph              TEXT NOT NULL,
    diet            TEXT NOT NULL,
    min_tank        TEXT NOT NULL,
    image_url       TEXT NOT NULL
);

INSERT INTO tmp_seed_fish
    (slug, name, category_id, description, detail, price, size,
     temperament, care_level, temperature, aquarium_level, ph, diet, min_tank, image_url)
VALUES
('dario-hysginon', 'Dario Hysginon', 'badis', 'A tiny, territorial badis from Myanmar with a red-and-blue patterned body. Best kept as a pair or small group in a heavily planted tank.', $d$Dario Hysginon Care Guide

A tiny, territorial badis from Myanmar with a red-and-blue patterned body. Best kept as a pair or small group in a heavily planted tank.

Tank Setup: A densely planted tank with caves, driftwood and gentle flow gives Dario Hysginon the cover it needs. A mature tank with biofilm and small live foods keeps it thriving.
Feeding Tips: Offer small live or frozen foods such as baby brine shrimp, daphnia and bloodworms.
Community & Behavior: Males defend small territories. Keep one male with several females, or a single specimen in a peaceful community.$d$, 45000.00, '3-4 cm', 'Semi-aggressive', 'Moderate', '20-26 °C', 'Bottom', '6.5-7.5', 'Carnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790931385/dario_hysginon.jpg'),
('tiger-badis', 'Tiger Badis', 'badis', 'A small, boldly striped badis with tiger-like bands. A micro predator that prefers live or frozen foods and plenty of cover.', $d$Tiger Badis Care Guide

A small, boldly striped badis with tiger-like bands. A micro predator that prefers live or frozen foods and plenty of cover.

Tank Setup: A densely planted tank with caves, driftwood and gentle flow gives Tiger Badis the cover it needs. A mature tank with biofilm and small live foods keeps it thriving.
Feeding Tips: Offer small live or frozen foods such as baby brine shrimp, daphnia and bloodworms.
Community & Behavior: Males defend small territories. Keep one male with several females, or a single specimen in a peaceful community.$d$, 50000.00, '4-5 cm', 'Semi-aggressive', 'Moderate', '20-26 °C', 'Bottom', '6.5-7.5', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790931306/tiger_badis.webp'),
('rosy-barb', 'Rosy Barb', 'barb', 'Hardy, active schooling barb with a rosy-red body. A great beginner fish for a roomy, planted community tank.', $d$Rosy Barb Care Guide

Hardy, active schooling barb with a rosy-red body. A great beginner fish for a roomy, planted community tank.

Tank Setup: A planted tank with open swimming space and moderate flow suits Rosy Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 12000.00, '6-7 cm', 'Peaceful', 'Easy', '20-26 °C', 'Middle', '6.0-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791607133/rosy_barb.webp'),
('ruby-barb', 'Ruby Barb', 'barb', 'Deep red schooling barb with dark markings. Active and hardy, best kept in a group of six or more.', $d$Ruby Barb Care Guide

Deep red schooling barb with dark markings. Active and hardy, best kept in a group of six or more.

Tank Setup: A planted tank with open swimming space and moderate flow suits Ruby Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 15000.00, '5-6 cm', 'Peaceful', 'Easy', '20-26 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791607056/ruby_barb.webp'),
('t-barb', 'T Barb', 'barb', 'A larger, torpedo-shaped barb with bold dark markings. Needs a spacious tank and a school of six or more.', $d$T Barb Care Guide

A larger, torpedo-shaped barb with bold dark markings. Needs a spacious tank and a school of six or more.

Tank Setup: A planted tank with open swimming space and moderate flow suits T Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 25000.00, '8-10 cm', 'Peaceful', 'Easy', '20-26 °C', 'Middle', '6.0-7.5', 'Omnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791606873/T_barb.webp'),
('odessa-barb', 'Odessa Barb', 'barb', 'Hardy schooling barb with a bright red stripe. Active and easy to keep in a planted community tank.', $d$Odessa Barb Care Guide

Hardy schooling barb with a bright red stripe. Active and easy to keep in a planted community tank.

Tank Setup: A planted tank with open swimming space and moderate flow suits Odessa Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 12000.00, '5-6 cm', 'Peaceful', 'Easy', '20-26 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791606818/odessa_barb.jpg'),
('filament-barb', 'Filament Barb', 'barb', 'Elegant barb whose males develop long, trailing fin filaments. Needs a roomy tank and a group of six or more.', $d$Filament Barb Care Guide

Elegant barb whose males develop long, trailing fin filaments. Needs a roomy tank and a group of six or more.

Tank Setup: A planted tank with open swimming space and moderate flow suits Filament Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 20000.00, '8-10 cm', 'Peaceful', 'Easy', '20-26 °C', 'Middle', '6.0-7.5', 'Omnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791606496/fila_barb.jpg'),
('denison-barb', 'Denison Barb', 'barb', 'Sleek, torpedo-shaped barb with a red stripe and black markings. Active and best kept in a large group.', $d$Denison Barb Care Guide

Sleek, torpedo-shaped barb with a red stripe and black markings. Active and best kept in a large group.

Tank Setup: A planted tank with open swimming space and moderate flow suits Denison Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 30000.00, '8-10 cm', 'Peaceful', 'Moderate', '20-26 °C', 'Middle', '6.0-7.5', 'Omnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791606321/denison_barb.png'),
('albino-cherry-barb', 'Albino Cherry Barb', 'barb', 'Albino form of the cherry barb with a pale pink body and red eyes. Peaceful and easy to keep.', $d$Albino Cherry Barb Care Guide

Albino form of the cherry barb with a pale pink body and red eyes. Peaceful and easy to keep.

Tank Setup: A planted tank with open swimming space and moderate flow suits Albino Cherry Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 15000.00, '4-5 cm', 'Peaceful', 'Easy', '20-26 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791606772/albino_cherry_barb.webp'),
('long-fin-cherry-barb', 'Long Fin Cherry Barb', 'barb', 'Cherry barb with flowing, elongated fins. Peaceful and colorful in a planted community tank.', $d$Long Fin Cherry Barb Care Guide

Cherry barb with flowing, elongated fins. Peaceful and colorful in a planted community tank.

Tank Setup: A planted tank with open swimming space and moderate flow suits Long Fin Cherry Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 18000.00, '4-5 cm', 'Peaceful', 'Easy', '20-26 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791606765/long_fin_cherry_barb.jpg'),
('cherry-barb', 'Cherry Barb', 'barb', 'Peaceful, deep-red schooling barb. Hardy and well suited to smaller planted tanks.', $d$Cherry Barb Care Guide

Peaceful, deep-red schooling barb. Hardy and well suited to smaller planted tanks.

Tank Setup: A planted tank with open swimming space and moderate flow suits Cherry Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 12000.00, '4-5 cm', 'Peaceful', 'Easy', '20-26 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791606612/cherry_barb.webp'),
('gold-tiger-barb', 'Gold Tiger Barb', 'barb', 'Golden form of the tiger barb with dark bands. Active and slightly nippy, best kept in a group.', $d$Gold Tiger Barb Care Guide

Golden form of the tiger barb with dark bands. Active and slightly nippy, best kept in a group.

Tank Setup: A planted tank with open swimming space and moderate flow suits Gold Tiger Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 14000.00, '5-6 cm', 'Semi-aggressive', 'Easy', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791606165/gold_tiger_barb.webp'),
('green-tiger-barb', 'Green Tiger Barb', 'barb', 'Green-bodied tiger barb with dark bands. Active and slightly nippy, best kept in a group.', $d$Green Tiger Barb Care Guide

Green-bodied tiger barb with dark bands. Active and slightly nippy, best kept in a group.

Tank Setup: A planted tank with open swimming space and moderate flow suits Green Tiger Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 14000.00, '5-6 cm', 'Semi-aggressive', 'Easy', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791553556/green_tiger_barb.jpg'),
('tiger-barb', 'Tiger Barb', 'barb', 'Classic striped barb with a lively, sometimes nippy temperament. Keep a group of six or more.', $d$Tiger Barb Care Guide

Classic striped barb with a lively, sometimes nippy temperament. Keep a group of six or more.

Tank Setup: A planted tank with open swimming space and moderate flow suits Tiger Barb. Keep the water clean and stable, and provide a group of six or more.
Feeding Tips: Accepts quality flakes or small pellets, plus occasional frozen or live foods.
Community & Behavior: Active and social. Keep a group of six or more to spread any nipping, and choose robust tank mates.$d$, 12000.00, '5-6 cm', 'Semi-aggressive', 'Easy', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791553475/tiger_barb.webp'),
('croaking-gourami', 'Croaking Gourami', 'gourami', 'A small, peaceful gourami that can produce audible croaking sounds. Does well in a calm, planted tank.', $d$Croaking Gourami Care Guide

A small, peaceful gourami that can produce audible croaking sounds. Does well in a calm, planted tank.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Croaking Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 25000.00, '6-7 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790931133/croaking_gourami.webp'),
('sparkling-gourami', 'Sparkling Gourami', 'gourami', 'Tiny gourami with sparkling blue-green markings. Calm and colorful, ideal for a nano planted tank.', $d$Sparkling Gourami Care Guide

Tiny gourami with sparkling blue-green markings. Calm and colorful, ideal for a nano planted tank.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Sparkling Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 30000.00, '3-4 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790931071/sparkling_gourami.webp'),
('blue-marble-gourami', 'Blue Marble Gourami', 'gourami', 'Blue-marbled color form of the three spot gourami. A hardy, medium-sized centerpiece fish.', $d$Blue Marble Gourami Care Guide

Blue-marbled color form of the three spot gourami. A hardy, medium-sized centerpiece fish.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Blue Marble Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 45000.00, '10-12 cm', 'Semi-aggressive', 'Moderate', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790929523/blue_marble_gourami.jpg'),
('three-spot-gourami', 'Three Spot Gourami', 'gourami', 'Classic medium-sized gourami with two dark spots and a peaceful disposition. Give it a calm, planted tank.', $d$Three Spot Gourami Care Guide

Classic medium-sized gourami with two dark spots and a peaceful disposition. Give it a calm, planted tank.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Three Spot Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 40000.00, '10-12 cm', 'Semi-aggressive', 'Moderate', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790929466/three_spot_gourami.webp'),
('moonlight-gourami', 'Moonlight Gourami', 'gourami', 'Silver-blue gourami with a peaceful nature. Best kept in a calm, well-planted tank.', $d$Moonlight Gourami Care Guide

Silver-blue gourami with a peaceful nature. Best kept in a calm, well-planted tank.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Moonlight Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 45000.00, '10-12 cm', 'Peaceful', 'Moderate', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790929426/moonlight_gourami.webp'),
('pearl-gourami', 'Pearl Gourami', 'gourami', 'Elegant gourami with a pearly, lace-like pattern. Peaceful and well suited to a community tank.', $d$Pearl Gourami Care Guide

Elegant gourami with a pearly, lace-like pattern. Peaceful and well suited to a community tank.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Pearl Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 50000.00, '10-12 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790929378/pearl_gourami.jpg'),
('dwarf-gourami', 'Dwarf Gourami', 'gourami', 'Compact, colorful gourami in a range of color forms. Best kept as a pair in a calm, planted tank.', $d$Dwarf Gourami Care Guide

Compact, colorful gourami in a range of color forms. Best kept as a pair in a calm, planted tank.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Dwarf Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 35000.00, '6-8 cm', 'Peaceful', 'Moderate', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790929353/dwarf_gourami.avif'),
('flame-gourami', 'Flame Gourami', 'gourami', 'Bright red-orange dwarf gourami variety. Colorful and calm, best kept as a pair.', $d$Flame Gourami Care Guide

Bright red-orange dwarf gourami variety. Colorful and calm, best kept as a pair.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Flame Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 40000.00, '6-8 cm', 'Peaceful', 'Moderate', '24-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790929327/flame_gourami.webp'),
('red-honey-gourami', 'Red Honey Gourami', 'gourami', 'Red color form of the honey gourami. Tiny, peaceful and ideal for a planted nano tank.', $d$Red Honey Gourami Care Guide

Red color form of the honey gourami. Tiny, peaceful and ideal for a planted nano tank.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Red Honey Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 30000.00, '4-5 cm', 'Peaceful', 'Easy', '22-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790929231/red_honey.webp'),
('honey-gourami', 'Honey Gourami', 'gourami', 'Small, golden-brown gourami with a gentle nature. A good choice for a peaceful community tank.', $d$Honey Gourami Care Guide

Small, golden-brown gourami with a gentle nature. A good choice for a peaceful community tank.

Tank Setup: A calm, well-planted tank with floating plants and subdued lighting brings out the best color in Honey Gourami. Keep the lid tight, as gouramis can jump.
Feeding Tips: Accepts quality flakes or micro pellets, plus occasional frozen or live foods.
Community & Behavior: Peaceful but territorial when breeding. Keep one male with several females and avoid boisterous tank mates.$d$, 25000.00, '4-5 cm', 'Peaceful', 'Easy', '22-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790929223/honey_gourami.png'),
('galaxy-rasbora', 'Galaxy Rasbora', 'rasbora', 'Tiny rasbora with a galaxy of pale spots and red fins. Best kept in a group in a mature, planted nano tank.', $d$Galaxy Rasbora Care Guide

Tiny rasbora with a galaxy of pale spots and red fins. Best kept in a group in a mature, planted nano tank.

Tank Setup: A mature, planted tank with subdued lighting and soft, slightly acidic water suits Galaxy Rasbora. Leave open space among the plants for the school to swim.
Feeding Tips: They have very small mouths, so feed fine flakes, micro pellets and tiny frozen foods.
Community & Behavior: Peaceful and social. Keep at least eight together and choose calm, similarly sized tank mates.$d$, 15000.00, '2-3 cm', 'Peaceful', 'Moderate', '22-28 °C', 'Middle', '6.0-7.0', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791552467/galaxy_rasbora.webp'),
('emerald-rasbora', 'Emerald Rasbora', 'rasbora', 'Small rasbora with a greenish, emerald sheen. Active and peaceful, best kept in a group.', $d$Emerald Rasbora Care Guide

Small rasbora with a greenish, emerald sheen. Active and peaceful, best kept in a group.

Tank Setup: A mature, planted tank with subdued lighting and soft, slightly acidic water suits Emerald Rasbora. Leave open space among the plants for the school to swim.
Feeding Tips: They have very small mouths, so feed fine flakes, micro pellets and tiny frozen foods.
Community & Behavior: Peaceful and social. Keep at least eight together and choose calm, similarly sized tank mates.$d$, 10000.00, '2-3 cm', 'Peaceful', 'Easy', '22-28 °C', 'Middle', '6.0-7.0', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791552393/emerald_rasbora.webp'),
('dwarf-rasbora', 'Dwarf Rasbora', 'rasbora', 'One of the smallest rasboras, with a red-brown body. Ideal for a planted nano tank in a group.', $d$Dwarf Rasbora Care Guide

One of the smallest rasboras, with a red-brown body. Ideal for a planted nano tank in a group.

Tank Setup: A mature, planted tank with subdued lighting and soft, slightly acidic water suits Dwarf Rasbora. Leave open space among the plants for the school to swim.
Feeding Tips: They have very small mouths, so feed fine flakes, micro pellets and tiny frozen foods.
Community & Behavior: Peaceful and social. Keep at least eight together and choose calm, similarly sized tank mates.$d$, 10000.00, '2-3 cm', 'Peaceful', 'Easy', '22-28 °C', 'Middle', '6.0-7.0', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1791552288/dwarf_rasbora.webp'),
('green-kubotai-rasbora', 'Green Kubotai Rasbora', 'rasbora', 'Vivid green rasbora with a dark lateral line. Active and peaceful, best kept in a group.', $d$Green Kubotai Rasbora Care Guide

Vivid green rasbora with a dark lateral line. Active and peaceful, best kept in a group.

Tank Setup: A mature, planted tank with subdued lighting and soft, slightly acidic water suits Green Kubotai Rasbora. Leave open space among the plants for the school to swim.
Feeding Tips: They have very small mouths, so feed fine flakes, micro pellets and tiny frozen foods.
Community & Behavior: Peaceful and social. Keep at least eight together and choose calm, similarly sized tank mates.$d$, 15000.00, '2-3 cm', 'Peaceful', 'Easy', '22-28 °C', 'Middle', '6.0-7.0', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790931811/green_kubotai_rasbora.webp'),
('chili-rasbora', 'Chili Rasbora', 'rasbora', 'Tiny, bright red rasbora. Perfect for a planted nano tank in a group of eight or more.', $d$Chili Rasbora Care Guide

Tiny, bright red rasbora. Perfect for a planted nano tank in a group of eight or more.

Tank Setup: A mature, planted tank with subdued lighting and soft, slightly acidic water suits Chili Rasbora. Leave open space among the plants for the school to swim.
Feeding Tips: They have very small mouths, so feed fine flakes, micro pellets and tiny frozen foods.
Community & Behavior: Peaceful and social. Keep at least eight together and choose calm, similarly sized tank mates.$d$, 12000.00, '2 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.0-7.0', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790931710/chili_rasbora.webp'),
('phoenix-rasbora', 'Phoenix Rasbora', 'rasbora', 'Small rasbora with a red-orange body and dark markings. Peaceful and best kept in a group.', $d$Phoenix Rasbora Care Guide

Small rasbora with a red-orange body and dark markings. Peaceful and best kept in a group.

Tank Setup: A mature, planted tank with subdued lighting and soft, slightly acidic water suits Phoenix Rasbora. Leave open space among the plants for the school to swim.
Feeding Tips: They have very small mouths, so feed fine flakes, micro pellets and tiny frozen foods.
Community & Behavior: Peaceful and social. Keep at least eight together and choose calm, similarly sized tank mates.$d$, 10000.00, '2-3 cm', 'Peaceful', 'Easy', '22-28 °C', 'Middle', '6.0-7.0', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790931655/phoenix_rasbora.webp'),
('least-rasbora', 'Least Rasbora', 'rasbora', 'Very small, delicate rasbora. Best kept in a mature, planted nano tank in a group.', $d$Least Rasbora Care Guide

Very small, delicate rasbora. Best kept in a mature, planted nano tank in a group.

Tank Setup: A mature, planted tank with subdued lighting and soft, slightly acidic water suits Least Rasbora. Leave open space among the plants for the school to swim.
Feeding Tips: They have very small mouths, so feed fine flakes, micro pellets and tiny frozen foods.
Community & Behavior: Peaceful and social. Keep at least eight together and choose calm, similarly sized tank mates.$d$, 10000.00, '2 cm', 'Peaceful', 'Easy', '22-28 °C', 'Middle', '6.0-7.0', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790931497/least_rasbora.webp'),
('boesemani-rainbowfish', 'Boesemani Rainbowfish', 'rainbowfish', 'Striking rainbowfish with a blue-and-orange split body. Active, hardy and best kept in a group.', $d$Boesemani Rainbowfish Care Guide

Striking rainbowfish with a blue-and-orange split body. Active, hardy and best kept in a group.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Boesemani Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 60000.00, '8-10 cm', 'Peaceful', 'Easy', '26-30 °C', 'Middle', '6.5-7.5', 'Omnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928108/boesemani.jpg'),
('dority-rainbowfish', 'Dority Rainbowfish', 'rainbowfish', 'A rare, colorful rainbowfish from New Guinea. Active and peaceful, best kept in a school.', $d$Dority Rainbowfish Care Guide

A rare, colorful rainbowfish from New Guinea. Active and peaceful, best kept in a school.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Dority Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 90000.00, '6-8 cm', 'Peaceful', 'Moderate', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790929050/dority.webp'),
('kali-tawa-rainbowfish', 'Kali Tawa Rainbowfish', 'rainbowfish', 'Colorful rainbowfish from the Kali Tawa river. Hardy and active, best kept in a group.', $d$Kali Tawa Rainbowfish Care Guide

Colorful rainbowfish from the Kali Tawa river. Hardy and active, best kept in a group.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Kali Tawa Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 80000.00, '6-8 cm', 'Peaceful', 'Moderate', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928964/kali_tawa.webp'),
('hapgood-rainbowfish', 'Hapgood Rainbowfish', 'rainbowfish', 'A colorful, uncommon rainbowfish. Active and peaceful, best kept in a school.', $d$Hapgood Rainbowfish Care Guide

A colorful, uncommon rainbowfish. Active and peaceful, best kept in a school.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Hapgood Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 90000.00, '6-8 cm', 'Peaceful', 'Moderate', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928883/hapgood.webp'),
('parkinsoni-rainbowfish', 'Parkinsoni Rainbowfish', 'rainbowfish', 'Spotted rainbowfish with a silvery body and dark markings. Active and hardy.', $d$Parkinsoni Rainbowfish Care Guide

Spotted rainbowfish with a silvery body and dark markings. Active and hardy.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Parkinsoni Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 60000.00, '7-9 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928861/parkisoni.webp'),
('unggalung-rainbowfish', 'Unggalung Rainbowfish', 'rainbowfish', 'A colorful, uncommon rainbowfish from New Guinea. Best kept in a school in a roomy tank.', $d$Unggalung Rainbowfish Care Guide

A colorful, uncommon rainbowfish from New Guinea. Best kept in a school in a roomy tank.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Unggalung Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 90000.00, '6-8 cm', 'Peaceful', 'Moderate', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928761/unggalung.jpg'),
('goyder-river-rainbowfish', 'Goyder River Rainbowfish', 'rainbowfish', 'A red-finned rainbowfish from the Goyder River. Active and peaceful, best kept in a group.', $d$Goyder River Rainbowfish Care Guide

A red-finned rainbowfish from the Goyder River. Active and peaceful, best kept in a group.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Goyder River Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 75000.00, '7-9 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928614/goyder.webp'),
('red-apple-rainbowfish', 'Red Apple Rainbowfish', 'rainbowfish', 'Deep red rainbowfish with a robust body. A stunning centerpiece for a large community tank.', $d$Red Apple Rainbowfish Care Guide

Deep red rainbowfish with a robust body. A stunning centerpiece for a large community tank.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Red Apple Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 90000.00, '10-12 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928532/red_apple.webp'),
('bleher-rainbowfish', 'Bleher''s Rainbowfish', 'rainbowfish', 'A colorful, uncommon rainbowfish. Active and peaceful, best kept in a school.', $d$Bleher's Rainbowfish Care Guide

A colorful, uncommon rainbowfish. Active and peaceful, best kept in a school.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Bleher's Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 90000.00, '6-8 cm', 'Peaceful', 'Moderate', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928496/bleheri.webp'),
('multi-striped-rainbowfish', 'Multi Striped Rainbowfish', 'rainbowfish', 'Rainbowfish with multiple dark stripes. Active and hardy, best kept in a group.', $d$Multi Striped Rainbowfish Care Guide

Rainbowfish with multiple dark stripes. Active and hardy, best kept in a group.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Multi Striped Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 70000.00, '7-9 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928451/multis.jpg'),
('dwarf-neon-rainbowfish', 'Dwarf Neon Rainbowfish', 'rainbowfish', 'Small, brilliantly blue rainbowfish with red fins. Active and peaceful, ideal for a planted tank.', $d$Dwarf Neon Rainbowfish Care Guide

Small, brilliantly blue rainbowfish with red fins. Active and peaceful, ideal for a planted tank.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Dwarf Neon Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 35000.00, '5-6 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928325/dwarf_neon.jpg'),
('indo-blue-rainbowfish', 'Indo Blue Rainbowfish', 'rainbowfish', 'Blue-toned rainbowfish from Indonesia. Active and peaceful, best kept in a school.', $d$Indo Blue Rainbowfish Care Guide

Blue-toned rainbowfish from Indonesia. Active and peaceful, best kept in a school.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Indo Blue Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 65000.00, '7-9 cm', 'Peaceful', 'Easy', '24-28 °C', 'Middle', '6.5-7.5', 'Omnivore', '150 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928266/indo_blue.jpg'),
('threadfin-rainbowfish', 'Threadfin Rainbowfish', 'rainbowfish', 'Delicate rainbowfish with long, thread-like fin extensions. Best kept in a calm, planted tank.', $d$Threadfin Rainbowfish Care Guide

Delicate rainbowfish with long, thread-like fin extensions. Best kept in a calm, planted tank.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Threadfin Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 25000.00, '3-4 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790928051/threadfin.jpg'),
('celebes-rainbowfish', 'Celebes Rainbowfish', 'rainbowfish', 'Elegant rainbowfish with yellow fins and a slender body. Best kept in a calm, planted tank.', $d$Celebes Rainbowfish Care Guide

Elegant rainbowfish with yellow fins and a slender body. Best kept in a calm, planted tank.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Celebes Rainbowfish. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 30000.00, '5-6 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790927952/celebes.jpg'),
('ivantsoff-blue-eye', 'Ivantsoff''s Blue Eye', 'rainbowfish', 'Small blue-eye with brilliant blue eyes and a silver body. Peaceful and ideal for a planted nano tank.', $d$Ivantsoff's Blue Eye Care Guide

Small blue-eye with brilliant blue eyes and a silver body. Peaceful and ideal for a planted nano tank.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Ivantsoff's Blue Eye. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 35000.00, '3-4 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790927868/ivantsoffi_blue_eye.webp'),
('delicate-blue-eye', 'Delicate Blue Eye', 'rainbowfish', 'A small, peaceful blue-eye. Best kept in a group in a calm, planted tank.', $d$Delicate Blue Eye Care Guide

A small, peaceful blue-eye. Best kept in a group in a calm, planted tank.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Delicate Blue Eye. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 30000.00, '3-4 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790927771/delicate_blue_eye.jpg'),
('neon-blue-eye', 'Neon Blue Eye', 'rainbowfish', 'Striking blue-eye with neon-blue markings. Active and peaceful, best kept in a group.', $d$Neon Blue Eye Care Guide

Striking blue-eye with neon-blue markings. Active and peaceful, best kept in a group.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Neon Blue Eye. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 35000.00, '3-4 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790927720/neon_blue_eye.jpg'),
('honey-blue-eye', 'Honey Blue Eye', 'rainbowfish', 'Small, honey-colored blue-eye. Peaceful and best kept in a group in a planted tank.', $d$Honey Blue Eye Care Guide

Small, honey-colored blue-eye. Peaceful and best kept in a group in a planted tank.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Honey Blue Eye. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 40000.00, '3-4 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790927625/honey_blue_eye.jpg'),
('pacific-blue-eye', 'Pacific Blue Eye', 'rainbowfish', 'Hardy blue-eye with a silvery body and blue eyes. A great nano-tank fish.', $d$Pacific Blue Eye Care Guide

Hardy blue-eye with a silvery body and blue eyes. A great nano-tank fish.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Pacific Blue Eye. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 25000.00, '3-4 cm', 'Peaceful', 'Easy', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790927576/pacific_blue_eye.webp'),
('forktail-blue-eye', 'Forktail Blue Eye', 'rainbowfish', 'Blue-eye with forked tail fins and bright blue eyes. Active and peaceful.', $d$Forktail Blue Eye Care Guide

Blue-eye with forked tail fins and bright blue eyes. Active and peaceful.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Forktail Blue Eye. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 30000.00, '3-4 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790927521/forktail_blue_eye.jpg'),
('spotted-blue-eye', 'Spotted Blue Eye', 'rainbowfish', 'Tiny blue-eye with dark spots and blue eyes. Best kept in a group in a planted nano tank.', $d$Spotted Blue Eye Care Guide

Tiny blue-eye with dark spots and blue eyes. Best kept in a group in a planted nano tank.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Spotted Blue Eye. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 35000.00, '3-4 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790927478/spotted_blue_eye.jpg'),
('red-neon-blue-eye', 'Red Neon Blue Eye', 'rainbowfish', 'Vivid red-and-blue blue-eye. A colorful nano fish, best kept in a group.', $d$Red Neon Blue Eye Care Guide

Vivid red-and-blue blue-eye. A colorful nano fish, best kept in a group.

Tank Setup: A roomy tank with open swimming space and moderate flow suits Red Neon Blue Eye. A dark substrate and good lighting show off its iridescence.
Feeding Tips: Accepts quality flakes or pellets, plus occasional frozen or live foods.
Community & Behavior: Active and peaceful. Keep a school of six or more and choose calm tank mates.$d$, 40000.00, '3-4 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.5-7.5', 'Omnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790927419/red_neon_blue_eye.webp'),
('lampeye-killifish', 'Lampeye Killifish', 'killifish', 'Small, peaceful killifish with glowing blue eyes. Best kept in a group in a planted tank.', $d$Lampeye Killifish Care Guide

Small, peaceful killifish with glowing blue eyes. Best kept in a group in a planted tank.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Lampeye Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 20000.00, '3-4 cm', 'Peaceful', 'Easy', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '40 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790922699/lampeye.webp'),
('golden-wonder-killifish', 'Golden Wonder Killifish', 'killifish', 'Hardy, golden killifish with a large mouth. Best kept with tank mates it cannot swallow.', $d$Golden Wonder Killifish Care Guide

Hardy, golden killifish with a large mouth. Best kept with tank mates it cannot swallow.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Golden Wonder Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 50000.00, '8-10 cm', 'Semi-aggressive', 'Easy', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '200 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790921354/golden_wonder.webp'),
('australe-killifish', 'Australe Killifish', 'killifish', 'Colorful lyretail killifish. Best kept as a pair in a planted species tank.', $d$Australe Killifish Care Guide

Colorful lyretail killifish. Best kept as a pair in a planted species tank.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Australe Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 60000.00, '5-6 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790921317/australe.jpg'),
('gardneri-killifish', 'Gardneri Killifish', 'killifish', 'Hardy, colorful killifish from West Africa. Best kept as a pair or trio.', $d$Gardneri Killifish Care Guide

Hardy, colorful killifish from West Africa. Best kept as a pair or trio.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Gardneri Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 60000.00, '5-6 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790921308/gardneri.jpg'),
('magnificus-killifish', 'Magnificus Killifish', 'killifish', 'Colorful killifish with elaborate fins. Best kept as a pair in a planted tank.', $d$Magnificus Killifish Care Guide

Colorful killifish with elaborate fins. Best kept as a pair in a planted tank.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Magnificus Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 70000.00, '5-6 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790783350/magnificus.jpg'),
('black-pearl-killifish', 'Black Pearl Killifish', 'killifish', 'Dark-bodied killifish with pearl-like markings. Best kept as a pair or trio.', $d$Black Pearl Killifish Care Guide

Dark-bodied killifish with pearl-like markings. Best kept as a pair or trio.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Black Pearl Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 60000.00, '5-6 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790783133/black_pearl.webp'),
('rocket-killifish', 'Rocket Killifish', 'killifish', 'Slender killifish with a rocket-like body shape. Best kept in a species tank.', $d$Rocket Killifish Care Guide

Slender killifish with a rocket-like body shape. Best kept in a species tank.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Rocket Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 70000.00, '5-6 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790783035/rocket.webp'),
('rubripinnis-killifish', 'Rubripinnis Killifish', 'killifish', 'Killifish with red fins and a colorful body. Best kept as a pair or trio.', $d$Rubripinnis Killifish Care Guide

Killifish with red fins and a colorful body. Best kept as a pair or trio.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Rubripinnis Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 70000.00, '5-6 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790782791/rubripinnis.webp'),
('nikiforovi-killifish', 'Nikiforovi Killifish', 'killifish', 'Colorful killifish with a peaceful nature. Best kept as a pair in a planted tank.', $d$Nikiforovi Killifish Care Guide

Colorful killifish with a peaceful nature. Best kept as a pair in a planted tank.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Nikiforovi Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 60000.00, '5-6 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790782716/nikiforovi.webp'),
('eggersi-red-killifish', 'Eggersi Red Killifish', 'killifish', 'Red color form of the Eggersi killifish. Best kept as a pair or trio in a species tank.', $d$Eggersi Red Killifish Care Guide

Red color form of the Eggersi killifish. Best kept as a pair or trio in a species tank.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Eggersi Red Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 70000.00, '4-5 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790782933/eggersi_red.jpg'),
('eggersi-blue-killifish', 'Eggersi Blue Killifish', 'killifish', 'Blue color form of the Eggersi killifish. Best kept as a pair or trio.', $d$Eggersi Blue Killifish Care Guide

Blue color form of the Eggersi killifish. Best kept as a pair or trio.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Eggersi Blue Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 70000.00, '4-5 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790782907/Eggersi_blue.jpg'),
('albino-rachovii-killifish', 'Albino Rachovii Killifish', 'killifish', 'Albino form of the Rachovii killifish with red eyes. Best kept in a species tank.', $d$Albino Rachovii Killifish Care Guide

Albino form of the Rachovii killifish with red eyes. Best kept in a species tank.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Albino Rachovii Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 80000.00, '4-5 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790782661/albino_rachovii.webp'),
('rachovii-killifish', 'Rachovii Killifish', 'killifish', 'Striking blue-and-red killifish. Best kept as a pair in a species tank.', $d$Rachovii Killifish Care Guide

Striking blue-and-red killifish. Best kept as a pair in a species tank.

Tank Setup: A species or calm community tank with gentle flow, plenty of cover and a tight-fitting lid suits Rachovii Killifish.
Feeding Tips: Feed small live or frozen foods such as baby brine shrimp, daphnia and bloodworms; many specimens also take micro pellets.
Community & Behavior: Best kept as a pair or small group in a species tank, or with calm, similarly sized tank mates.$d$, 75000.00, '4-5 cm', 'Peaceful', 'Moderate', '22-26 °C', 'Middle', '6.0-7.0', 'Carnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790782556/Screenshot_20250117_002721_Instagram.webp'),
('emperor-tetra', 'Emperor Tetra', 'tetra', 'Classic tetra with a purple sheen and a black stripe. Peaceful and best kept in a group.', $d$Emperor Tetra Care Guide

Classic tetra with a purple sheen and a black stripe. Peaceful and best kept in a group.

Tank Setup: A planted tank with subdued lighting, soft water and open swimming space suits Emperor Tetra. Avoid adding it to a brand-new tank.
Feeding Tips: Accept small flakes or micro pellets, with occasional frozen or live foods.
Community & Behavior: Peaceful and social. Keep at least six to eight together and avoid large or boisterous tank mates.$d$, 15000.00, '4-5 cm', 'Peaceful', 'Easy', '23-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790780049/emperor_tetra.webp'),
('blue-emperor-tetra', 'Blue Emperor Tetra', 'tetra', 'Blue color form of the emperor tetra. Peaceful and best kept in a planted tank.', $d$Blue Emperor Tetra Care Guide

Blue color form of the emperor tetra. Peaceful and best kept in a planted tank.

Tank Setup: A planted tank with subdued lighting, soft water and open swimming space suits Blue Emperor Tetra. Avoid adding it to a brand-new tank.
Feeding Tips: Accept small flakes or micro pellets, with occasional frozen or live foods.
Community & Behavior: Peaceful and social. Keep at least six to eight together and avoid large or boisterous tank mates.$d$, 18000.00, '4-5 cm', 'Peaceful', 'Easy', '23-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790780226/blue_emperor_tetra.webp'),
('black-emperor-tetra', 'Black Emperor Tetra', 'tetra', 'Dark-bodied emperor tetra. Peaceful and best kept in a group.', $d$Black Emperor Tetra Care Guide

Dark-bodied emperor tetra. Peaceful and best kept in a group.

Tank Setup: A planted tank with subdued lighting, soft water and open swimming space suits Black Emperor Tetra. Avoid adding it to a brand-new tank.
Feeding Tips: Accept small flakes or micro pellets, with occasional frozen or live foods.
Community & Behavior: Peaceful and social. Keep at least six to eight together and avoid large or boisterous tank mates.$d$, 18000.00, '4-5 cm', 'Peaceful', 'Easy', '23-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790780154/black_emperor_tetra.webp'),
('penguin-tetra', 'Penguin Tetra', 'tetra', 'Tetra with a distinctive black band and red tail. Active and peaceful.', $d$Penguin Tetra Care Guide

Tetra with a distinctive black band and red tail. Active and peaceful.

Tank Setup: A planted tank with subdued lighting, soft water and open swimming space suits Penguin Tetra. Avoid adding it to a brand-new tank.
Feeding Tips: Accept small flakes or micro pellets, with occasional frozen or live foods.
Community & Behavior: Peaceful and social. Keep at least six to eight together and avoid large or boisterous tank mates.$d$, 15000.00, '4-5 cm', 'Peaceful', 'Easy', '23-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790925661/penguin_tetra.webp'),
('red-tail-penguin-tetra', 'Red Tail Penguin Tetra', 'tetra', 'Penguin tetra with a bright red tail. Best kept in a group in a planted tank.', $d$Red Tail Penguin Tetra Care Guide

Penguin tetra with a bright red tail. Best kept in a group in a planted tank.

Tank Setup: A planted tank with subdued lighting, soft water and open swimming space suits Red Tail Penguin Tetra. Avoid adding it to a brand-new tank.
Feeding Tips: Accept small flakes or micro pellets, with occasional frozen or live foods.
Community & Behavior: Peaceful and social. Keep at least six to eight together and avoid large or boisterous tank mates.$d$, 18000.00, '4-5 cm', 'Peaceful', 'Easy', '23-28 °C', 'Middle', '6.0-7.5', 'Omnivore', '75 L', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790925691/red_tail_penguin.webp');

-- 1. PRODUCTS: temporary business id, rekeyed to PRD_<id> after insert.
INSERT INTO products
    (product_id, product_name, product_description, product_detail_description,
     product_status, category_id, product_slug)
SELECT 'seed:' || s.slug, s.name, s.description, s.detail,
       'ACTIVE', c.id, s.slug
FROM tmp_seed_fish s
JOIN categories c ON c.category_id = s.category_id
WHERE NOT EXISTS (
    SELECT 1 FROM products p WHERE lower(p.product_slug) = lower(s.slug)
);

UPDATE products
SET product_id = 'PRD_' || id
WHERE product_id LIKE 'seed:%';

-- 2. VARIANTS: one default variant per new product.
INSERT INTO variants (variant_id, variant_sku, variant_price, variant_stock, product_id)
SELECT s.slug || '-default', 'FISH-' || upper(s.slug), s.price, 50, p.id
FROM tmp_seed_fish s
JOIN products p ON lower(p.product_slug) = lower(s.slug)
WHERE NOT EXISTS (
    SELECT 1 FROM variants v WHERE lower(v.variant_id) = lower(s.slug || '-default')
);

-- 3. MEDIA: one product-scoped image per new product, then thumbnail FK.
INSERT INTO media (media_id, media_url, product_id, is_deleted)
SELECT 'MED_' || s.slug, s.image_url, p.id, FALSE
FROM tmp_seed_fish s
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
      SELECT 1 FROM tmp_seed_fish s WHERE lower(s.slug) = lower(p.product_slug)
  );

-- 4. Congo albino variant image (variant-scoped, product_id stays NULL).
INSERT INTO media (media_id, media_url, variant_id, is_deleted)
SELECT 'MED_congo-tetra-albino', 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790925608/albino_congo.jpg', v.id, FALSE
FROM variants v
WHERE v.variant_id = 'congo-tetra-albino'
  AND NOT EXISTS (
      SELECT 1 FROM media m
      WHERE m.variant_id = v.id AND m.media_url = 'https://res.cloudinary.com/ibk3z2nt/image/upload/v1790925608/albino_congo.jpg'
  );

-- 5. ATTRIBUTE VALUES: shared (attribute, value) pairs for the 8 fish specs.
WITH spec AS (
    SELECT slug, 'temperature'       AS attribute_id, temperature    AS value FROM tmp_seed_fish
    UNION ALL SELECT slug, 'temperament',       temperament    FROM tmp_seed_fish
    UNION ALL SELECT slug, 'care-level',        care_level     FROM tmp_seed_fish
    UNION ALL SELECT slug, 'aquarium-level',    aquarium_level FROM tmp_seed_fish
    UNION ALL SELECT slug, 'minimum-tank-size', min_tank       FROM tmp_seed_fish
    UNION ALL SELECT slug, 'size',              size           FROM tmp_seed_fish
    UNION ALL SELECT slug, 'ph',                ph             FROM tmp_seed_fish
    UNION ALL SELECT slug, 'diet',              diet           FROM tmp_seed_fish
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
    SELECT slug, 'temperature'       AS attribute_id, temperature    AS value FROM tmp_seed_fish
    UNION ALL SELECT slug, 'temperament',       temperament    FROM tmp_seed_fish
    UNION ALL SELECT slug, 'care-level',        care_level     FROM tmp_seed_fish
    UNION ALL SELECT slug, 'aquarium-level',    aquarium_level FROM tmp_seed_fish
    UNION ALL SELECT slug, 'minimum-tank-size', min_tank       FROM tmp_seed_fish
    UNION ALL SELECT slug, 'size',              size           FROM tmp_seed_fish
    UNION ALL SELECT slug, 'ph',                ph             FROM tmp_seed_fish
    UNION ALL SELECT slug, 'diet',              diet           FROM tmp_seed_fish
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

DROP TABLE tmp_seed_fish;
