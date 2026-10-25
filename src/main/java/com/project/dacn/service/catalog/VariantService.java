package com.project.dacn.service.catalog;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.VariantCreateRequest;
import com.project.dacn.dto.request.catalog.VariantUpdateRequest;
import com.project.dacn.dto.response.catalog.VariantResponse;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

@Service
@Transactional
@RequiredArgsConstructor
public class VariantService {

    private static final TypeReference<VariantResponse> VARIANT_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<PageResponse<VariantResponse>> VARIANT_PAGE = new TypeReference<>() {
    };

    private final VariantRepository repo;
    private final ProductRepository productRepo;
    private final CatalogMapper mapper;
    private final CatalogCache cache;

    public PageResponse<VariantResponse> list(Pageable pageable) {
        return list(pageable, false);
    }

    public PageResponse<VariantResponse> list(Pageable pageable, boolean includeHidden) {
        if (includeHidden || !CatalogCacheKeys.isCacheablePage(pageable)) {
            return listFromDb(pageable, includeHidden);
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.VARIANT_SCOPE);
        if (generation.isEmpty()) {
            return listFromDb(pageable, false);
        }
        String key = CatalogCacheKeys.variantList(generation.getAsLong(), pageable);
        Optional<PageResponse<VariantResponse>> cached = cache.get(key, VARIANT_PAGE);
        if (cached.isPresent()) {
            return overlayVariantStock(cached.get());
        }
        PageResponse<VariantResponse> response = listFromDb(pageable, false);
        cache.put(key, scrubVariantStock(response), CatalogCacheKeys.VARIANT_PAGE_TTL);
        return response;
    }

    public PageResponse<VariantResponse> listByProductSlug(String slug, Pageable pageable) {
        return listByProductSlug(slug, pageable, false);
    }

    public PageResponse<VariantResponse> listByProductSlug(String slug, Pageable pageable,
            boolean includeHidden) {
        if (includeHidden || !CatalogCacheKeys.isCacheablePage(pageable)) {
            return listByProductSlugFromDb(slug, pageable, includeHidden);
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.VARIANT_SCOPE);
        if (generation.isEmpty()) {
            return listByProductSlugFromDb(slug, pageable, false);
        }
        String key = CatalogCacheKeys.variantByProductSlug(slug, generation.getAsLong(), pageable);
        Optional<PageResponse<VariantResponse>> cached = cache.get(key, VARIANT_PAGE);
        if (cached.isPresent()) {
            return overlayVariantStock(cached.get());
        }
        PageResponse<VariantResponse> response = listByProductSlugFromDb(slug, pageable, false);
        cache.put(key, scrubVariantStock(response), CatalogCacheKeys.VARIANT_PAGE_TTL);
        return response;
    }

    private PageResponse<VariantResponse> listFromDb(Pageable pageable, boolean includeHidden) {
        Page<Variant> page = includeHidden
                ? repo.findByDeletedFalse(pageable)
                : repo.findByDeletedFalseAndProduct_Status(ProductStatus.ACTIVE, pageable);
        return PageResponse.from(page, page.map(mapper::toVariantResponse).getContent());
    }

    private PageResponse<VariantResponse> listByProductSlugFromDb(String slug, Pageable pageable,
            boolean includeHidden) {
        requireProductBySlug(slug, includeHidden);
        Page<Variant> page = repo.findByProduct_SlugIgnoreCase(slug, pageable);
        return PageResponse.from(page, page.map(mapper::toVariantResponse).getContent());
    }

    public VariantResponse get(String variantId) {
        return get(variantId, false);
    }

    public VariantResponse get(String variantId, boolean includeHidden) {
        if (includeHidden) {
            return mapper.toVariantResponse(requireVisible(variantId, true));
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.VARIANT_SCOPE);
        if (generation.isEmpty()) {
            return mapper.toVariantResponse(requireVisible(variantId, false));
        }
        String key = CatalogCacheKeys.variantDetail(generation.getAsLong(), variantId);
        Optional<VariantResponse> cached = cache.get(key, VARIANT_RESPONSE);
        if (cached.isPresent()) {
            // No stock row means no live variant (soft-deleted): send stock as unknown.
            return withStock(cached.get(), variantStock(variantId));
        }
        VariantResponse response = mapper.toVariantResponse(requireVisible(variantId, false));
        cache.put(key, withStock(response, null), CatalogCacheKeys.VARIANT_DETAIL_TTL);
        return response;
    }

    public VariantResponse create(VariantCreateRequest request) {
        Variant entity = mapper.toVariant(request);
        entity.setProduct(productRepo.findByProductId(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "product not found: " + request.productId())));
        VariantResponse response = mapper.toVariantResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.VARIANT_SCOPE, CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    public VariantResponse update(String variantId, VariantUpdateRequest request) {
        Variant entity = require(variantId);
        mapper.updateVariant(request, entity);
        VariantResponse response = mapper.toVariantResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.VARIANT_SCOPE, CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    /**
     * PUT replace. Currently merge-only (scalar DTO, NOT NULL columns):
     * kept separate from {@link #update} so PUT/PATCH semantics can diverge.
     */
    public VariantResponse replace(String variantId, VariantUpdateRequest request) {
        return update(variantId, request);
    }

    public void delete(String variantId) {
        Variant entity = require(variantId);
        entity.setDeleted(true);
        repo.save(entity);
        cache.bumpAfterCommit(CatalogCacheKeys.VARIANT_SCOPE, CatalogCacheKeys.PRODUCT_SCOPE);
    }

    private Variant require(String variantId) {
        return repo.findByVariantId(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("variant not found: " + variantId));
    }

    private Variant requireVisible(String variantId, boolean includeHidden) {
        Variant variant = require(variantId);
        if (!includeHidden && variant.getProduct().getStatus() != ProductStatus.ACTIVE) {
            throw new ResourceNotFoundException("variant not found: " + variantId);
        }
        return variant;
    }

    private void requireProductBySlug(String slug, boolean includeHidden) {
        productRepo.findBySlugIgnoreCase(slug)
                .filter(product -> includeHidden || product.getStatus() == ProductStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("product not found: " + slug));
    }

    private Integer variantStock(String variantId) {
        return repo.findStockByVariantId(variantId)
                .map(VariantRepository.VariantStockView::getStock)
                .orElse(null);
    }

    private PageResponse<VariantResponse> scrubVariantStock(PageResponse<VariantResponse> page) {
        return newPageResponse(page, page.getContent().stream()
                .map(response -> withStock(response, null))
                .toList());
    }

    private PageResponse<VariantResponse> overlayVariantStock(PageResponse<VariantResponse> page) {
        List<String> variantIds = page.getContent().stream()
                .map(VariantResponse::variantId)
                .filter(Objects::nonNull)
                .toList();
        Map<String, Integer> stockByVariant = new HashMap<>();
        if (!variantIds.isEmpty()) {
            for (VariantRepository.VariantStockView view : repo.findStockByVariantIdIn(variantIds)) {
                stockByVariant.put(view.getVariantId(), view.getStock());
            }
        }
        return newPageResponse(page, page.getContent().stream()
                .map(response -> withStock(response, stockByVariant.get(response.variantId())))
                .toList());
    }

    private static PageResponse<VariantResponse> newPageResponse(
            PageResponse<VariantResponse> page, List<VariantResponse> content) {
        return new PageResponse<>(content, page.getPage(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    private static VariantResponse withStock(VariantResponse response, Integer stock) {
        return new VariantResponse(response.id(), response.variantId(), response.sku(),
                response.price(), stock, response.productId(), response.productName(),
                response.deleted(), response.createdAt(), response.updatedAt());
    }
}
