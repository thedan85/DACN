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

@Service
@Transactional
@RequiredArgsConstructor
public class VariantService {

    private final VariantRepository repo;
    private final ProductRepository productRepo;
    private final CatalogMapper mapper;

    public PageResponse<VariantResponse> list(Pageable pageable) {
        return list(pageable, false);
    }

    public PageResponse<VariantResponse> list(Pageable pageable, boolean includeHidden) {
        Page<Variant> page = includeHidden
                ? repo.findByDeletedFalse(pageable)
                : repo.findByDeletedFalseAndProduct_Status(ProductStatus.ACTIVE, pageable);
        return PageResponse.from(page, page.map(mapper::toVariantResponse).getContent());
    }

    public PageResponse<VariantResponse> listByProductSlug(String slug, Pageable pageable) {
        return listByProductSlug(slug, pageable, false);
    }

    public PageResponse<VariantResponse> listByProductSlug(String slug, Pageable pageable,
            boolean includeHidden) {
        requireProductBySlug(slug, includeHidden);
        Page<Variant> page = repo.findByProduct_SlugIgnoreCase(slug, pageable);
        return PageResponse.from(page, page.map(mapper::toVariantResponse).getContent());
    }

    public VariantResponse get(String variantId) {
        return get(variantId, false);
    }

    public VariantResponse get(String variantId, boolean includeHidden) {
        return mapper.toVariantResponse(requireVisible(variantId, includeHidden));
    }

    public VariantResponse create(VariantCreateRequest request) {
        Variant entity = mapper.toVariant(request);
        entity.setProduct(productRepo.findByProductId(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "product not found: " + request.productId())));
        return mapper.toVariantResponse(repo.save(entity));
    }

    public VariantResponse update(String variantId, VariantUpdateRequest request) {
        Variant entity = require(variantId);
        mapper.updateVariant(request, entity);
        return mapper.toVariantResponse(repo.save(entity));
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
}
