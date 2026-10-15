# Cloudinary Product Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add server-side image upload to Cloudinary for product thumbnails and variant media under `home/<category-path>/<productId>/`.

**Architecture:** New `UploadController` + `UploadService` + `ProductFolderResolver` reuse the existing `CloudinaryService`; product JSON APIs stay untouched, upload happens first, DB persists after.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (webmvc, data-jpa, validation), Cloudinary `cloudinary-http5:2.4.0`, JUnit 5 + Mockito + AssertJ, MockMultipartFile for controller tests.

**Spec:** `docs/2026-10-15-cloudinary-product-upload-design.md` (working copy also at `docs/superpowers/specs/2026-10-15-cloudinary-product-upload-design.md`; the `docs/superpowers/` tree is gitignored by project convention so the committed copy lives under `docs/`).

## Global Constraints

- No database migration: no new columns, no `publicId` storage, old Cloudinary assets are kept on replace.
- No gallery/multi-file endpoint: exactly two upload endpoints (product thumbnail, variant image).
- File validation: images only (`jpg/jpeg/png/webp/gif` content-type AND extension), max 10MB per file, non-empty.
- Folder rule: `home/` + category ancestor slugs (root dropped when chain length > 1, kept when length == 1) + `product.productId` slug; variant uploads reuse the parent product folder.
- Filename (`public_id`): `<productId>-<epochMillis>` for thumbnails, `<productId>-<sku-slug>-<epochMillis>` for variants; `overwrite:false`.
- Security: inherit existing `permitAll` from `SecurityConfig`; add a code comment noting production lockdown.
- All responses use the existing `ApiResponse<T>` envelope; not-found uses `ResourceNotFoundException` (404), validation uses `IllegalArgumentException` (400), Cloudinary failure uses `CloudinaryException` mapped to 502.
- TDD: failing test first for every task; commit per task.

## Review Focus

- Uppercase extension/content-type (`PHOTO.JPG`, `image/JPEG`) accepted while `photo.JPG.exe` rejected — pinned in Task 3 validation tests.
- Exactly-10MB file accepted, 10MB+1 byte rejected — pinned in Task 3 boundary test.
- Product sitting directly on a root category (chain length 1) still yields `home/<root>/<product>` instead of `home/<product>` — pinned in Task 2 test.
- Variant whose parent product has no category yields `home/<productId>` without NPE — pinned in Task 2 + Task 3 tests.
- Cloudinary succeeds but DB save throws: response is 5xx and thumbnail/Media is unchanged (no partial URL persisted) — pinned in Task 3 ordering test.

---

### Task 1: CloudinaryService publicId overload

**Files:**
- Modify: `src/main/java/com/project/dacn/integration/cloudinary/CloudinaryService.java`
- Test: `src/test/java/com/project/dacn/service/integration/CloudinaryServiceTest.java` (new dir `service/integration/` is fine; or place under `src/test/java/com/project/dacn/integration/CloudinaryServiceTest.java` to mirror main — prefer the mirror path)

**Interfaces:**
- Consumes: `com.cloudinary.Cloudinary.uploader().upload(Object, Map)` (existing).
- Produces: `Map<String, Object> upload(byte[] bytes, String folder, String publicId)` and `String uploadAndGetUrl(byte[] bytes, String folder, String publicId)` for Task 3. Existing 2-arg methods stay and delegate with `publicId = null`.

- [ ] **Step 1: Write the failing test** in the mirror-path test file: mock `Cloudinary` + `Uploader`, stub `upload` to return `Map.of("secure_url", "https://x/y.jpg")`, call `upload(bytes, "home/fish/tetra/congo-tetra", "congo-tetra-123")`, assert the params map contains `folder=home/fish/tetra/congo-tetra`, `public_id=congo-tetra-123`, `overwrite=false`, and `uploadAndGetUrl` returns the URL. Also assert 2-arg `upload(bytes, folder)` still works (params without `public_id`).
- [ ] **Step 2: Run test to verify it fails** — Run: `./mvnw -q test -Dtest=CloudinaryServiceTest` Expected: FAIL (`upload(byte[], String, String)` not defined).
- [ ] **Step 3: Implement overloads** in `CloudinaryService.java`: `upload(byte[], String, String)` builds params via `ObjectUtils.asMap("folder", folder, "public_id", publicId (omit when null), "resource_type", "auto", "overwrite", false, "unique_filename", false)`, same `try/catch (IOException) -> CloudinaryException` pattern; 2-arg methods delegate.
- [ ] **Step 4: Run test to verify it passes** — Run: `./mvnw -q test -Dtest=CloudinaryServiceTest` Expected: PASS.
- [ ] **Step 5: Commit** — `git add <both files>; git commit -m "feat: cloudinary upload with publicId"`

### Task 2: ProductFolderResolver + slugify

**Files:**
- Create: `src/main/java/com/project/dacn/service/catalog/ProductFolderResolver.java`
- Test: `src/test/java/com/project/dacn/service/ProductFolderResolverTest.java`

**Interfaces:**
- Consumes: `Product.getProductId()`, `Product.getName()`, `Product.getCategory()`; `Category.getSlug()`, `Category.getParent()`.
- Produces: `String resolve(Product product)` and `static String slugify(String input)` (lowercase, `[^a-z0-9]+ -> -`, trim `-`) for Task 3.

- [ ] **Step 1: Write failing tests**: `tetra chain -> home/fish/tetra/congo-tetra` (build Category livestock->fish->tetra in memory, Product `congo-tetra`); `badis chain -> home/fish/badis/dario-dario`; `epiphyte chain -> home/aquatic-plants/epiphyte/anubias-nana`; `root-direct product -> home/livestock/lonely-prod` (chain length 1 keeps root); `null category -> home/congo-tetra`; `slugify("Congo Tetra!") == congo-tetra`.
- [ ] **Step 2: Run to verify failures** — Run: `./mvnw -q test -Dtest=ProductFolderResolverTest` Expected: FAIL (class not found).
- [ ] **Step 3: Implement `ProductFolderResolver`** as `@Component` with `resolve` walking parents to root, reversing, dropping index 0 only when size > 1, appending `slugify(productId != null ? productId : name)`, prefixing `home/`.
- [ ] **Step 4: Run to verify pass** — Run: `./mvnw -q test -Dtest=ProductFolderResolverTest` Expected: PASS.
- [ ] **Step 5: Commit** — `git add <both>; git commit -m "feat: product folder resolver for cloudinary"`

### Task 3: UploadService + UploadResponse

**Files:**
- Create: `src/main/java/com/project/dacn/dto/response/catalog/UploadResponse.java` — `record UploadResponse(String url, String folder, String productId, String variantId, String mediaId)` (mediaId null for thumbnail).
- Create: `src/main/java/com/project/dacn/service/catalog/UploadService.java`
- Test: `src/test/java/com/project/dacn/service/UploadServiceTest.java`

**Interfaces:**
- Consumes: `CloudinaryService.uploadAndGetUrl(byte[], String, String)`, `ProductFolderResolver.resolve/slugify`, `ProductRepository.findByProductId`, `VariantRepository.findByVariantId`, `MediaService.create(MediaCreateRequest)`, `ProductRepository.save`.
- Produces: `UploadResponse uploadProductThumbnail(String productId, MultipartFile file)` and `UploadResponse uploadVariantImage(String variantId, MultipartFile file)` for Task 4.

- [ ] **Step 1: Write failing tests** (`@ExtendWith(MockitoExtension.class)`, mocks for repos/CloudinaryService/resolver/MediaService, AssertJ): happy thumbnail path asserts folder `home/fish/tetra/congo-tetra`, `product.thumbnail` set to returned URL, `UploadResponse` fields; happy variant path asserts `mediaService.create` called with `(url, null, variantId)` and same product folder; rejections for empty file, `text/plain` type, `photo.JPG.exe` double extension, exactly-10MB passes vs 10MB+1 fails, unknown product/variant -> `ResourceNotFoundException`, Cloudinary throw -> no `productRepo.save` / no `mediaService.create` call.
- [ ] **Step 2: Run to verify failures** — Run: `./mvnw -q test -Dtest=UploadServiceTest` Expected: FAIL (class not found).
- [ ] **Step 3: Implement `UploadService`** (`@Service @RequiredArgsConstructor @Transactional`): `validateImage` (empty/size/type+extension per Global Constraints), `publicId` builders with `System.currentTimeMillis()`, upload-first-then-persist ordering, variant path loads variant -> `variant.getProduct()` and builds `UploadResponse` with `mediaId` taken from the returned `MediaResponse.mediaId()`.
- [ ] **Step 4: Run to verify pass** — Run: `./mvnw -q test -Dtest=UploadServiceTest,ProductFolderResolverTest,CloudinaryServiceTest` Expected: PASS.
- [ ] **Step 5: Commit** — `git add <3 files>; git commit -m "feat: upload service for product and variant images"`

### Task 4: UploadController + error mapping + controller tests

**Files:**
- Create: `src/main/java/com/project/dacn/controller/catalog/UploadController.java`
- Modify: `src/main/java/com/project/dacn/common/exception/GlobalExceptionHandler.java:63` (add `@ExceptionHandler(CloudinaryException.class)` -> 502 before generic handler)
- Test: `src/test/java/com/project/dacn/controller/UploadControllerTest.java` (`@WebMvcTest(UploadController.class)`, mock `UploadService`, `MockMultipartFile`)

**Interfaces:**
- Consumes: Task 3 `uploadProductThumbnail` / `uploadVariantImage`, `ApiResponse.ok`, `GlobalExceptionHandler` mappings.
- Produces: `POST /api/uploads/products/{productId}/thumbnail` (`file` part, `multipart/form-data`, 200 `ApiResponse<UploadResponse>`) and `POST /api/uploads/variants/{variantId}` (same shape). No other routes.

- [ ] **Step 1: Write failing controller tests**: happy thumbnail 200 + envelope `code:200`, happy variant 200, service throwing `ResourceNotFoundException` -> 404, `IllegalArgumentException` -> 400, `CloudinaryException` -> 502 with `UPLOAD_FAILED` body.
- [ ] **Step 2: Run to verify failures** — Run: `./mvnw -q test -Dtest=UploadControllerTest` Expected: FAIL (controller not found).
- [ ] **Step 3: Implement `UploadController`** (`@RestController @RequestMapping("/api/uploads") @RequiredArgsConstructor`, `@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)` x2, `@RequestParam("file") MultipartFile` — note `@RequestParam` not `@RequestPart` for form fields), plus the `CloudinaryException` handler returning `ApiResponse.of(502, "UPLOAD_FAILED: ...", null)` and a `// permitAll inherited; restrict in prod` comment.
- [ ] **Step 4: Run to verify pass** — Run: `./mvnw -q test -Dtest=UploadControllerTest,UploadServiceTest,ProductFolderResolverTest,CloudinaryServiceTest` Expected: PASS.
- [ ] **Step 5: Run full suite** — Run: `./mvnw -q test` Expected: all PASS (H2 `test` profile provides dummy Cloudinary creds; no network).
- [ ] **Step 6: Commit** — `git add <3 files>; git commit -m "feat: upload endpoints for product and variant images"`
