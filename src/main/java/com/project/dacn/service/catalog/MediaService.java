package com.project.dacn.service.catalog;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.MediaCreateRequest;
import com.project.dacn.dto.request.catalog.MediaUpdateRequest;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.MediaRepository;
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
public class MediaService {

    private final MediaRepository repo;
    private final ProductRepository productRepo;
    private final VariantRepository variantRepo;
    private final CatalogMapper mapper;

    public PageResponse<MediaResponse> list(Pageable pageable) {
        Page<Media> page = repo.findByDeletedFalse(pageable);
        return PageResponse.from(page, page.map(mapper::toMediaResponse).getContent());
    }

    public PageResponse<MediaResponse> listByProduct(String productId, Pageable pageable) {
        requireProduct(productId);
        Page<Media> page = repo.findByProduct_ProductId(productId, pageable);
        return PageResponse.from(page, page.map(mapper::toMediaResponse).getContent());
    }

    public PageResponse<MediaResponse> listByVariant(String variantId, Pageable pageable) {
        requireVariant(variantId);
        Page<Media> page = repo.findByVariant_VariantId(variantId, pageable);
        return PageResponse.from(page, page.map(mapper::toMediaResponse).getContent());
    }

    public MediaResponse get(String mediaId) {
        return mapper.toMediaResponse(require(mediaId));
    }

    public MediaResponse create(MediaCreateRequest request) {
        return mapper.toMediaResponse(createEntity(request));
    }

    public Media createEntity(MediaCreateRequest request) {
        boolean hasProduct = request.productId() != null;
        boolean hasVariant = request.variantId() != null;
        if (hasProduct == hasVariant) {
            throw new IllegalArgumentException("media must reference exactly one of product or variant");
        }
        Media entity = mapper.toMedia(request);
        if (request.productId() != null) {
            entity.setProduct(productRepo.findByProductId(request.productId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "product not found: " + request.productId())));
        } else {
            entity.setVariant(variantRepo.findByVariantId(request.variantId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "variant not found: " + request.variantId())));
        }
        return repo.save(entity);
    }

    public MediaResponse update(String mediaId, MediaUpdateRequest request) {
        Media entity = require(mediaId);
        mapper.updateMedia(request, entity);
        return mapper.toMediaResponse(repo.save(entity));
    }

    /**
     * PUT replace. Currently merge-only: kept separate from {@link #update}
     * so PUT/PATCH semantics can diverge.
     */
    public MediaResponse replace(String mediaId, MediaUpdateRequest request) {
        return update(mediaId, request);
    }

    public void delete(String mediaId) {
        Media entity = require(mediaId);
        entity.setDeleted(true);
        repo.save(entity);
    }

    private Media require(String mediaId) {
        return repo.findByMediaId(mediaId)
                .orElseThrow(() -> new ResourceNotFoundException("media not found: " + mediaId));
    }

    private void requireProduct(String productId) {
        if (productRepo.findByProductId(productId).isEmpty()) {
            throw new ResourceNotFoundException("product not found: " + productId);
        }
    }

    private void requireVariant(String variantId) {
        if (variantRepo.findByVariantId(variantId).isEmpty()) {
            throw new ResourceNotFoundException("variant not found: " + variantId);
        }
    }
}
