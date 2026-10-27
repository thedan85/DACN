package com.project.dacn.service.catalog;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.DuplicateResourceException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.common.util.BusinessId;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.request.catalog.ProductFilter;
import com.project.dacn.dto.request.catalog.ProductUpdateRequest;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.dto.response.catalog.ProductAttributeValueResponse;
import com.project.dacn.dto.response.catalog.ProductDetailResponse;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.dto.response.catalog.VariantAttributeValueResponse;
import com.project.dacn.dto.response.catalog.VariantDetailResponse;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.engagement.Review;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.mapper.engagement.EngagementMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import com.project.dacn.repository.catalog.ProductPriceRange;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.engagement.ReviewRepository;
import com.project.dacn.spec.ProductSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ProductService {

    private static final Pattern SLUG_PATTERN =
            Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private static final int MAX_SLUG_LENGTH = 255;

    /**
     * Literal paths under /api/v1/products that would shadow a product detail
     * route if used as a slug. A product named "Search" becomes "search-2".
     */
    private static final Set<String> RESERVED_SLUGS = Set.of("search");

    private static final TypeReference<ProductResponse> PRODUCT_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<PageResponse<ProductResponse>> PRODUCT_PAGE = new TypeReference<>() {
    };

    private final ProductRepository repo;
    private final CategoryRepository categoryRepo;
    private final VariantRepository variantRepo;
    private final MediaRepository mediaRepo;
    private final ProductAttributeValueRepository productAttributeValueRepo;
    private final VariantAttributeValueRepository variantAttributeValueRepo;
    private final ReviewRepository reviewRepo;
    private final CatalogMapper mapper;
    private final EngagementMapper engagementMapper;
    private final CatalogCache cache;

    public PageResponse<ProductResponse> list(Pageable pageable) {
        if (!CatalogCacheKeys.isCacheablePage(pageable)) {
            return listFromDb(pageable);
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.PRODUCT_SCOPE);
        if (generation.isEmpty()) {
            return listFromDb(pageable);
        }
        String key = CatalogCacheKeys.productList(generation.getAsLong(), pageable);
        Optional<PageResponse<ProductResponse>> cached = cache.get(key, PRODUCT_PAGE);
        if (cached.isPresent()) {
            return overlayProductStock(cached.get());
        }
        PageResponse<ProductResponse> response = listFromDb(pageable);
        cache.put(key, scrubProductStock(response), CatalogCacheKeys.PRODUCT_PAGE_TTL);
        return response;
    }

    public PageResponse<ProductResponse> search(ProductFilter filter, Pageable pageable) {
        // Strip before both the query and the cache key, so " a" and "a" agree.
        // Blank categoryId means "no filter"; the key builder collapses it too.
        String categoryId = filter.categoryId() == null ? null : filter.categoryId().strip();
        ProductFilter effective = new ProductFilter(
                filter.keyword() == null ? null : filter.keyword().strip(),
                filter.status(),
                categoryId == null || categoryId.isEmpty() ? null : categoryId,
                filter.minPrice(), filter.maxPrice(), filter.sort());
        validatePriceRange(effective);
        // Sorting is driven by ProductSort inside ProductSpecs. Drop any Sort
        // parsed from the generic `sort` query param by the Pageable resolver,
        // otherwise Spring Data appends an invalid property order.
        Pageable cleanPageable = withoutSort(pageable);
        if (!CatalogCacheKeys.isCacheableSearch(effective, cleanPageable)) {
            return searchFromDb(effective, cleanPageable);
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.PRODUCT_SCOPE);
        if (generation.isEmpty()) {
            return searchFromDb(effective, cleanPageable);
        }
        String key = CatalogCacheKeys.productSearch(generation.getAsLong(), effective, cleanPageable);
        Optional<PageResponse<ProductResponse>> cached = cache.get(key, PRODUCT_PAGE);
        if (cached.isPresent()) {
            return overlayProductStock(cached.get());
        }
        PageResponse<ProductResponse> response = searchFromDb(effective, cleanPageable);
        if (!response.getContent().isEmpty()) {
            cache.put(key, scrubProductStock(response), CatalogCacheKeys.PRODUCT_PAGE_TTL);
        }
        return response;
    }

    private PageResponse<ProductResponse> listFromDb(Pageable pageable) {
        Page<Product> page = repo.findByStatus(ProductStatus.ACTIVE, pageable);
        return toPageResponse(page);
    }

    private PageResponse<ProductResponse> searchFromDb(ProductFilter filter, Pageable cleanPageable) {
        // A category filter covers the whole subtree: a parent category page
        // must list products filed under any descendant category.
        Set<String> categoryIds = filter.categoryId() == null
                ? null
                : categorySubtree(filter.categoryId());
        // DELETED is hidden by @SQLRestriction on Product; route explicit DELETED
        // requests through the native admin query that bypasses that restriction.
        if (filter.status() == ProductStatus.DELETED) {
            // Native LIKE has no null handling: an empty keyword matches every name.
            String keyword = filter.keyword() == null ? "" : filter.keyword();
            Page<Product> page = categoryIds == null
                    ? repo.findByStatusIncludingDeleted(
                            ProductStatus.DELETED.name(), keyword, cleanPageable)
                    : repo.findByStatusIncludingDeletedInCategories(
                            ProductStatus.DELETED.name(), keyword, categoryIds, cleanPageable);
            return toPageResponse(page);
        }
        Page<Product> page = repo.findAll(ProductSpecs.filter(filter, categoryIds), cleanPageable);
        return toPageResponse(page);
    }

    /**
     * The requested category plus every descendant. The seeded category tree is
     * small and shallow, so resolving it in memory beats a DB-specific recursive
     * CTE. Switch to a recursive query if the tree grows large.
     */
    private Set<String> categorySubtree(String rootId) {
        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (Category category : categoryRepo.findByDeletedFalse()) {
            Category parent = category.getParent();
            if (parent != null) {
                childrenByParent.computeIfAbsent(parent.getCategoryId(), key -> new ArrayList<>())
                        .add(category.getCategoryId());
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(rootId));
        while (!pending.isEmpty()) {
            String id = pending.poll();
            if (ids.add(id)) {
                pending.addAll(childrenByParent.getOrDefault(id, List.of()));
            }
        }
        return ids;
    }

    public ProductResponse getBySlug(String slug) {
        return getBySlug(slug, false);
    }

    /**
     * @param includeHidden true only for callers holding the catalog-manage
     *                      permission; DRAFT/INACTIVE are otherwise 404.
     */
    public ProductResponse getBySlug(String slug, boolean includeHidden) {
        if (includeHidden) {
            return toResponse(requireBySlug(slug, true));
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.PRODUCT_SCOPE);
        if (generation.isEmpty()) {
            return toResponse(requireBySlug(slug, false));
        }
        String key = CatalogCacheKeys.productSlug(generation.getAsLong(), slug);
        Optional<ProductResponse> cached = cache.get(key, PRODUCT_RESPONSE);
        if (cached.isPresent()) {
            return withStock(cached.get(), productStock(cached.get().productId()));
        }
        ProductResponse response = toResponse(requireBySlug(slug, false));
        cache.put(key, withStock(response, null), CatalogCacheKeys.PRODUCT_DETAIL_TTL);
        return response;
    }

    /**
     * Storefront detail payload: the cached flat product plus its relation
     * lists, which are read fresh so variant price/stock and reviews stay live.
     * customerId in reviews is only populated for admins/employees.
     */
    public ProductDetailResponse getDetail(String slug, boolean includeHidden, Pageable reviewPage) {
        ProductResponse product = getBySlug(slug, includeHidden);

        List<ProductAttributeValueResponse> attributes = productAttributeValueRepo
                .findByProduct_ProductId(product.productId()).stream()
                .map(mapper::toProductAttributeValueResponse)
                .toList();
        List<Variant> variants = variantRepo.findByProduct_ProductId(product.productId());
        Map<String, List<VariantAttributeValueResponse>> attributesByVariant =
                variantAttributeValueRepo.findByVariant_Product_ProductId(product.productId()).stream()
                        .map(mapper::toVariantAttributeValueResponse)
                        .collect(Collectors.groupingBy(VariantAttributeValueResponse::variantId));
        Map<String, List<MediaResponse>> mediaByVariant = mediaRepo
                .findByVariant_Product_ProductId(product.productId()).stream()
                .map(mapper::toMediaResponse)
                .collect(Collectors.groupingBy(MediaResponse::variantId));
        List<VariantDetailResponse> variantDetails = variants.stream()
                .map(variant -> {
                    List<MediaResponse> media =
                            mediaByVariant.getOrDefault(variant.getVariantId(), List.of());
                    // A product with no real variant choice has no variant-level
                    // media; its thumbnail is the default variant's image.
                    if (media.isEmpty() && variants.size() == 1
                            && product.thumbnailMediaId() != null) {
                        media = mediaRepo.findByMediaId(product.thumbnailMediaId())
                                .map(mapper::toMediaResponse)
                                .map(List::of)
                                .orElse(List.of());
                    }
                    return new VariantDetailResponse(
                            variant.getVariantId(), variant.getSku(), variant.getPrice(),
                            variant.getStock(),
                            attributesByVariant.getOrDefault(variant.getVariantId(), List.of()),
                            media);
                })
                // The storefront selects variants[0], so keep the default form first.
                .sorted(Comparator.comparingInt(variant ->
                        variant.variantId().equals(product.slug() + "-default") ? 0 : 1))
                .toList();
        Function<Review, ReviewResponse> toReview =
                includeHidden ? engagementMapper::toStaffReviewResponse : engagementMapper::toReviewResponse;
        Page<Review> reviews = reviewRepo
                .findByVariant_Product_ProductIdOrderByCreatedAtDesc(product.productId(), reviewPage);
        return new ProductDetailResponse(product, attributes, variantDetails,
                PageResponse.from(reviews, reviews.getContent().stream().map(toReview).toList()));
    }

    public ProductResponse create(ProductCreateRequest request) {
        // Resolve the category before mapping so a bad categoryId fails with 404
        // before any entity state is built.
        Category category = request.categoryId() == null ? null
                : categoryRepo.findByCategoryId(request.categoryId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "category not found: " + request.categoryId()));

        Product entity = mapper.toProduct(request);
        entity.setName(normalizeName(request.name()));
        entity.setSlug(resolveCreateSlug(request.slug(), entity.getName()));
        entity.setCategory(category);
        if (entity.getStatus() == null) {
            entity.setStatus(ProductStatus.ACTIVE);
        }

        // product_id is PRD_<id>, and the identity id only exists after the
        // insert. @PrePersist assigns a throwaway PRD_<hex> so the NOT NULL
        // column is satisfied, then we rewrite it to the readable form.
        entity = repo.saveAndFlush(entity);
        entity.setProductId("PRD_" + entity.getId());
        // A freshly created product has no variants yet, so skip the price
        // lookup here: querying would auto-flush the changed natural id and
        // Hibernate rejects that before the transaction commits.
        ProductResponse response = toResponse(repo.save(entity), null);
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE, CatalogCacheKeys.VARIANT_SCOPE);
        return response;
    }

    public ProductResponse update(String productId, ProductUpdateRequest request) {
        Product entity = require(productId);
        ProductStatus previousStatus = entity.getStatus();

        mapper.updateProduct(request, entity);
        entity.setStatus(request.status() == null ? previousStatus : request.status());
        if (request.name() != null) {
            entity.setName(normalizeName(request.name()));
        }
        applySlugChange(entity, request.slug());
        if (request.thumbnailMediaId() != null) {
            entity.setThumbnail(resolveThumbnail(entity, request.thumbnailMediaId()));
        }
        if (request.categoryId() != null) {
            entity.setCategory(categoryRepo.findByCategoryId(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "category not found: " + request.categoryId())));
        }
        // PATCH merge: null categoryId means "not provided", keep existing.
        ProductResponse response = toResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE, CatalogCacheKeys.VARIANT_SCOPE);
        return response;
    }

    /**
     * PUT replace semantics: {@code null} categoryId explicitly detaches the
     * product from its category. Scalar nulls still merge (columns are NOT NULL,
     * so true null-wipe is impossible with the current DTO).
     */
    public ProductResponse replace(String productId, ProductUpdateRequest request) {
        Product entity = require(productId);
        ProductStatus previousStatus = entity.getStatus();

        mapper.updateProduct(request, entity);
        entity.setStatus(request.status() == null ? previousStatus : request.status());
        if (request.name() != null) {
            entity.setName(normalizeName(request.name()));
        }
        applySlugChange(entity, request.slug());
        if (request.thumbnailMediaId() != null) {
            entity.setThumbnail(resolveThumbnail(entity, request.thumbnailMediaId()));
        } else {
            entity.setThumbnail(null);
        }
        if (request.categoryId() != null) {
            entity.setCategory(categoryRepo.findByCategoryId(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "category not found: " + request.categoryId())));
        } else {
            entity.setCategory(null);
        }
        ProductResponse response = toResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE, CatalogCacheKeys.VARIANT_SCOPE);
        return response;
    }

    public void delete(String productId) {
        Product entity = require(productId);
        entity.setStatus(ProductStatus.DELETED);
        repo.save(entity);
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE, CatalogCacheKeys.VARIANT_SCOPE);
    }

    private PageResponse<ProductResponse> toPageResponse(Page<Product> page) {
        Map<Long, ProductPriceRange> ranges = priceRanges(page.getContent());
        List<ProductResponse> content = page.getContent().stream()
                .map(product -> toResponse(product, ranges.get(product.getId())))
                .toList();
        return PageResponse.from(page, content);
    }

    /**
     * Batch-load min/max variant prices for a whole product page.
     */
    private Map<Long, ProductPriceRange> priceRanges(List<Product> products) {
        List<Long> productIds = products.stream()
                .map(Product::getId)
                .filter(Objects::nonNull)
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return variantRepo.findPriceRangesByProductIds(productIds).stream()
                .collect(Collectors.toMap(ProductPriceRange::productId, Function.identity()));
    }

    private ProductResponse toResponse(Product product) {
        return toResponse(product, priceRange(product.getId()));
    }

    private ProductPriceRange priceRange(Long productId) {
        if (productId == null) {
            return null;
        }
        return variantRepo.findPriceRangesByProductIds(List.of(productId)).stream()
                .findFirst()
                .orElse(null);
    }

    private ProductResponse toResponse(Product product, ProductPriceRange range) {
        return mapper.toProductResponse(product,
                range == null ? null : range.minPrice(),
                range == null ? null : range.maxPrice(),
                range == null ? 0 : range.totalStock().intValue());
    }

    private int productStock(String productId) {
        return variantRepo.findStockByProductId(productId)
                .map(VariantRepository.ProductStockView::getTotalStock)
                .filter(Objects::nonNull)
                .map(Long::intValue)
                .orElse(0);
    }

    private PageResponse<ProductResponse> scrubProductStock(PageResponse<ProductResponse> page) {
        return newPageResponse(page, page.getContent().stream()
                .map(response -> withStock(response, null))
                .toList());
    }

    private PageResponse<ProductResponse> overlayProductStock(PageResponse<ProductResponse> page) {
        List<String> productIds = page.getContent().stream()
                .map(ProductResponse::productId)
                .filter(Objects::nonNull)
                .toList();
        Map<String, Integer> stockByProduct = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (VariantRepository.ProductStockView view
                    : variantRepo.findStockByProductIdIn(productIds)) {
                stockByProduct.put(view.getProductId(),
                        view.getTotalStock() == null ? 0 : view.getTotalStock().intValue());
            }
        }
        return newPageResponse(page, page.getContent().stream()
                .map(response -> withStock(response,
                        stockByProduct.getOrDefault(response.productId(), 0)))
                .toList());
    }

    private static PageResponse<ProductResponse> newPageResponse(
            PageResponse<ProductResponse> page, List<ProductResponse> content) {
        return new PageResponse<>(content, page.getPage(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    private static ProductResponse withStock(ProductResponse response, Integer stock) {
        return new ProductResponse(response.productId(), response.slug(), response.name(),
                response.description(), response.detailDescription(), response.thumbnail(),
                response.thumbnailMediaId(), response.status(), response.categoryId(),
                response.categoryName(), response.minPrice(), response.maxPrice(), stock,
                response.createdAt(), response.updatedAt());
    }

    private Product require(String productId) {
        return repo.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("product not found: " + productId));
    }

    /**
     * Storefront detail lookup: products that are not ACTIVE (DRAFT/INACTIVE)
     * must look identical to missing ones. Soft-deleted rows are already hidden
     * by the entity's SQL restriction.
     */
    private Product requireBySlug(String slug, boolean includeHidden) {
        return repo.findBySlugIgnoreCase(slug)
                .filter(product -> includeHidden || product.getStatus() == ProductStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("product not found: " + slug));
    }

    /**
     * A thumbnail must be an existing, non-deleted media row that belongs to
     * this product, so a product cannot point at another product's image.
     */
    private Media resolveThumbnail(Product product, String mediaId) {
        Media media = mediaRepo.findByMediaId(mediaId)
                .orElseThrow(() -> new ResourceNotFoundException("media not found: " + mediaId));
        if (media.getProduct() == null
                || !Objects.equals(media.getProduct().getId(), product.getId())) {
            throw new ResourceNotFoundException("media not found: " + mediaId);
        }
        return media;
    }

    private String resolveCreateSlug(String rawSlug, String name) {
        if (rawSlug == null || rawSlug.isBlank()) {
            return nextAvailableSlug(BusinessId.slug(name));
        }
        String slug = normalizeExplicitSlug(rawSlug);
        if (repo.countBySlugIncludingDeleted(slug) > 0) {
            throw new DuplicateResourceException("slug already exists: " + slug);
        }
        return slug;
    }

    private void applySlugChange(Product entity, String rawSlug) {
        if (rawSlug == null || rawSlug.isBlank()) {
            return;
        }
        String slug = normalizeExplicitSlug(rawSlug);
        if (slug.equalsIgnoreCase(entity.getSlug())) {
            return;
        }
        if (repo.countBySlugIncludingDeletedAndIdNot(slug, entity.getId()) > 0) {
            throw new DuplicateResourceException("slug already exists: " + slug);
        }
        entity.setSlug(slug);
    }

    /**
     * Auto-generated slugs must not collide with live or soft-deleted rows,
     * because the DB unique index reserves deleted products' slugs too.
     */
    private String nextAvailableSlug(String base) {
        String candidate = base;
        int suffix = 2;
        if (RESERVED_SLUGS.contains(candidate)) {
            candidate = withSuffix(base, suffix++);
        }
        while (repo.countBySlugIncludingDeleted(candidate) > 0) {
            candidate = withSuffix(base, suffix++);
        }
        return candidate;
    }

    /**
     * Appends {@code -<suffix>} while keeping the slug within the column width,
     * so a long auto-generated base slug cannot overflow product_slug when it
     * collides.
     */
    private static String withSuffix(String base, int suffix) {
        String suffixText = "-" + suffix;
        int maxBase = MAX_SLUG_LENGTH - suffixText.length();
        String trimmed = base.length() > maxBase ? base.substring(0, maxBase) : base;
        trimmed = trimmed.replaceAll("-+$", "");
        return trimmed + suffixText;
    }

    private static String normalizeExplicitSlug(String raw) {
        String slug = raw.strip().toLowerCase(Locale.ROOT);
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "slug must contain only lowercase letters, digits and single dashes: " + raw);
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException(
                    "slug must be at most " + MAX_SLUG_LENGTH + " characters: " + raw);
        }
        if (RESERVED_SLUGS.contains(slug)) {
            throw new IllegalArgumentException("slug is reserved: " + slug);
        }
        return slug;
    }

    /**
     * Names are display-only (not unique, not used as a key), so the only
     * cleanup is trimming and collapsing internal whitespace.
     */
    private static String normalizeName(String raw) {
        String name = raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("product name must not be blank");
        }
        return name;
    }

    private static void validatePriceRange(ProductFilter filter) {
        if (filter.minPrice() != null && filter.minPrice().signum() < 0) {
            throw new IllegalArgumentException("min_price must be greater than or equal to 0");
        }
        if (filter.maxPrice() != null && filter.maxPrice().signum() < 0) {
            throw new IllegalArgumentException("max_price must be greater than or equal to 0");
        }
        if (filter.minPrice() != null && filter.maxPrice() != null
                && filter.minPrice().compareTo(filter.maxPrice()) > 0) {
            throw new IllegalArgumentException("min_price must be less than or equal to max_price");
        }
    }

    private static Pageable withoutSort(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return Pageable.unpaged();
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
