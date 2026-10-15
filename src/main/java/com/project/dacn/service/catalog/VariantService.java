package com.project.dacn.service.catalog;

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
        Page<Variant> page = repo.findByDeletedFalse(pageable);
        return PageResponse.from(page, page.map(mapper::toVariantResponse).getContent());
    }

    public PageResponse<VariantResponse> listByProduct(String productId, Pageable pageable) {
        requireProduct(productId);
        Page<Variant> page = repo.findByProduct_ProductId(productId, pageable);
        return PageResponse.from(page, page.map(mapper::toVariantResponse).getContent());
    }

    public VariantResponse get(String variantId) {
        return mapper.toVariantResponse(require(variantId));
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

    private void requireProduct(String productId) {
        if (productRepo.findByProductId(productId).isEmpty()) {
            throw new ResourceNotFoundException("product not found: " + productId);
        }
    }
}
