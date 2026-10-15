# Design: Cloudinary Product Image Uploads

Date: 2026-10-15
Status: Approved (Sections 1-3) — pending spec review
Scope: Backend only, uni project, no commercial hardening.

## 1. Intent (agreed)

Enable image upload to Cloudinary when adding/updating products.
Folder tree must match existing Cloudinary layout (`Home/Fish/Tetra`, …)
and extend as `home/<category-path>/<product>/`, e.g.
`home/fish/tetra/congo-tetra` holding 2 images (normal + variant).
Keep old Cloudinary assets on replace (no deletes, no `publicId` columns).

Decisions locked:
- Separate `/api/uploads` endpoints, product JSON APIs stay JSON-only.
- Folder segments: category ancestor slugs + product segment.
- Image split: product endpoint → `Product.thumbnail`; variant endpoint → `Media(variantId)`.
- No gallery endpoint.
- Validation: images only, 10MB max.
- No schema migration.

## 2. Current state (verified)

- `integration/cloudinary/CloudinaryConfig.java:18` builds `Cloudinary` bean from
  `CLOUDINARY_CLOUD_NAME/API_KEY/API_SECRET` (`application.yml:33`).
- `integration/cloudinary/CloudinaryService.java:20,32,39` has
  `upload(byte[], folder)`, `uploadAndGetUrl()`, `delete()` — zero callers.
- `controller/catalog/ProductController.java:56,62` PUT/PATCH takes
  `ProductUpdateRequest.thumbnail: String` (URL only).
- `controller/catalog/MediaController.java:55,61` PUT/PATCH takes `MediaUpdateRequest(url)`.
- `entity/catalog/Product.java:44` `thumbnail VARCHAR(500)`;
  `entity/catalog/Media.java:32` `url NOT NULL`, optional `product`/`variant` FK.
- `entity/catalog/Category.java:37` self-parent; seeds `V5__seed_categories.sql`:
  `livestock → fish → tetra`, `fish → badis`, `aquatic-plants → epiphyte/moss`.
- `V6__seed_fish_products.sql`: `congo-tetra` has variants
  `congo-tetra-default` (Normal) + `congo-tetra-albino`.
- `config/SecurityConfig.java:15` is `anyRequest().permitAll()` — uploads inherit this (no new auth).

## 3. Architecture

New `catalog` upload slice reusing existing `CloudinaryService`:

```
UploadController → UploadService → { ProductRepository, VariantRepository,
  Category (via Product), MediaService or MediaRepository, CloudinaryService }
                                  → ProductFolderResolver (pure helper)
```

No changes to `ProductController`, `MediaController`, `Product`, `Media` tables.

Files:
- NEW `service/catalog/ProductFolderResolver.java` — pure static/pure-component folder builder.
- NEW `service/catalog/UploadService.java` — validation + orchestration.
- NEW `controller/catalog/UploadController.java` — 2 multipart endpoints.
- EDIT `integration/cloudinary/CloudinaryService.java` — add
  `upload(byte[], folder, publicId)` overload (existing 2-arg stays, delegates).
- NEW tests: `ProductFolderResolverTest`, `UploadServiceTest` (mocked),
  `UploadControllerTest` (`@WebMvcTest` multipart).

## 4. Folder + filename rule

`ProductFolderResolver.resolve(Product product) -> String`:

1. Walk `product.category` + `parent` chain to root, collect `slug.toLowerCase()`, reverse
   (root-first). Example `congo-tetra` → `[livestock, fish, tetra]`.
2. Drop index 0 only when chain length > 1 (the root with no parent: `livestock`,
   `equipments`, `consumables`, `decors-substrate`). If product sits directly on a root
   (chain length 1), keep that segment. This reproduces existing `Home/fish/tetra` (not
   `Home/livestock/fish/tetra`).
3. Slugify each remaining segment (already slugs in DB) + product segment.
4. Product segment = `product.productId` (already slug, e.g. `congo-tetra`,
   `dario-dario`). Rationale: stable when display `name` is edited; user example
   `congo` is shorthand for `congo-tetra`. Slugify defensively anyway
   (`lower, [^a-z0-9]+ → -`, trim `-`).
5. Prefix `home/`. Results:
   - `congo-tetra` (tetra→fish→livestock) → `home/fish/tetra/congo-tetra`
   - `dario-dario` (badis→fish→livestock) → `home/fish/badis/dario-dario`
   - new `Anubias Nana` in `epiphyte` → `home/aquatic-plants/epiphyte/anubias-nana`
6. Variant uploads: load `variant.product`, resolve same folder. Both Normal and
   Albino land in `home/fish/tetra/congo-tetra`.

Filename (`publicId`, extension stripped):
- Product thumbnail: `<productId>-<epochMillis>` e.g. `congo-tetra-1729001234567`.
- Variant: `<productId>-<variantSku-slug>-<epochMillis>`
  e.g. `congo-tetra-fish-congo-tetra-albino-1729001234567`.
- Timestamp suffix avoids Cloudinary overwrite collisions; old assets kept.

Cloudinary call: `upload(bytes, folder, publicId)` with
`{folder, public_id, resource_type: auto, overwrite: false, unique_filename: false}`.
`overwrite:false` + timestamp guarantees no silent replace.

## 5. Endpoints + data flow

Base: `/api/uploads`, `consumes = multipart/form-data`.

### 5.1 `POST /api/uploads/products/{productId}/thumbnail` (single `file`)

1. `requireProduct(productId)` → 404 if missing.
2. `validateImage(file)` (Sec. 6).
3. `folder = resolver.resolve(product)`.
4. `url = cloudinary.uploadAndGetUrl(bytes, folder, publicId)` — upload FIRST.
5. `product.thumbnail = url; productRepo.save(product)`.
6. Return `200 {productId, url, folder}` (reuse `ProductResponse` or small record
   `UploadResponse(url, folder)` — prefer small record to avoid leaking entity).

Re-upload overwrites `thumbnail` field only; old Cloudinary file retained.

### 5.2 `POST /api/uploads/variants/{variantId}` (single `file`)

1. `requireVariant(variantId)` → 404; get `variant.product` (must exist).
2. Validate, resolve folder from parent product, `publicId` with SKU.
3. Upload first, then `mediaService.create(new MediaCreateRequest(url, null, variantId))`
   (or direct repo save — prefer via `MediaService` to reuse exactly-one-target check).
4. Return `200 MediaResponse + folder`.

FE add/update flow (unchanged JSON + 2-step upload):
1. `POST /api/products` or `PUT /api/products/{id}` (JSON, thumbnail optional/blank).
2. Upload cover via 5.1.
3. For each variant with image: upload via 5.2.

No transaction across Cloudinary + DB (distributed). Order (upload→persist) ensures
DB never points at failed upload; orphan Cloudinary file on DB-save failure is
accepted uni-scope (documented, no cleanup job).

## 6. Validation + errors

`UploadService.validateImage(MultipartFile)`:
- null/empty → `400 INVALID_FILE "file must not be empty"`.
- size > 10MB (`10 * 1024 * 1024`) → `400 INVALID_FILE "max 10MB"`.
- content-type allowlist: `image/jpeg, image/png, image/webp, image/gif`
  + extension allowlist `jpg jpeg png webp gif` (both must pass; browsers vary).
- `bytes = file.getBytes()`; `IOException` → `CloudinaryException` path.

Error mapping (reuse existing handlers; add `@ExceptionHandler` only if missing):
- `IllegalArgumentException` (validation) → 400.
- `ResourceNotFoundException` → 404.
- `CloudinaryException` → 502 `UPLOAD_FAILED` (Cloudinary reachable but failed).

## 7. Security

No change: inherits `permitAll` from `SecurityConfig`. Note in code comment that
production should restrict `/api/uploads/**` to admin/employee roles when auth
is tightened.

## 8. Testing

- `ProductFolderResolverTest`: tetra chain, badis chain, standalone + deep
  (`aquatic-plants/epiphyte`), slugify (`"Congo Tetra" → congo-tetra`), root-strip.
- `UploadServiceTest` (Mockito): mocks `CloudinaryService`, repos; verifies folder
  passed, thumbnail set, `Media` created with `variantId`, validation rejects
  (empty, oversize, txt), upload-first ordering (no DB save on Cloudinary throw).
- `UploadControllerTest` (`@WebMvcTest` + `MockMultipartFile`): happy path 200,
  400 on bad type, 404 on unknown id; no real Cloudinary.
- Manual: upload Congo Normal + Albino, verify Cloudinary dashboard shows both
  under `home/fish/tetra/congo-tetra`, `GET /api/products/congo-tetra` thumbnail
  set, `GET /api/variants/{id}/media` returns row.

## 9. Non-goals (explicit)

- No `publicId` columns, no delete/orphan cleanup, no folder renames/moves.
- No gallery/multi-file endpoint.
- No FE changes in this spec (FE calls the two endpoints directly).
- No direct browser-to-Cloudinary signed uploads.

## 10. Rollout

1. `CloudinaryService` overload (backward-compatible).
2. `ProductFolderResolver` + tests.
3. `UploadService` + tests.
4. `UploadController` + tests.
5. Manual Cloudinary dashboard verification with Congo Tetra.
