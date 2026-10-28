package com.project.dacn.service.catalog;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.dto.request.catalog.MediaCreateRequest;
import com.project.dacn.dto.request.catalog.MediaUpdateRequest;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
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
    private final CatalogCache cache;

    public PageResponse<MediaResponse> list(Pageable pageable, boolean includeHidden) {
        Page<Media> page = includeHidden
                ? repo.findByDeletedFalse(pageable)
                : repo.findVisible(ProductStatus.ACTIVE, pageable);
        return PageResponse.from(page, page.map(mapper::toMediaResponse).getContent());
    }

    public PageResponse<MediaResponse> listByProduct(String productId, Pageable pageable,
            boolean includeHidden) {
        Product product = requireProduct(productId);
        if (!includeHidden && product.getStatus() != ProductStatus.ACTIVE) {
            throw new ResourceNotFoundException("product not found: " + productId);
        }
        Page<Media> page = repo.findByProduct_ProductId(productId, pageable);
        if (page.getTotalElements() == 0) {
            // Merged products keep their images on variants, so fall back to them.
            page = repo.findByVariant_Product_ProductId(productId, pageable);
        }
        return PageResponse.from(page, page.map(mapper::toMediaResponse).getContent());
    }

    public PageResponse<MediaResponse> listByVariant(String variantId, Pageable pageable,
            boolean includeHidden) {
        Variant variant = requireVariant(variantId);
        if (!includeHidden && variant.getProduct().getStatus() != ProductStatus.ACTIVE) {
            throw new ResourceNotFoundException("variant not found: " + variantId);
        }
        Page<Media> page = repo.findByVariant_VariantId(variantId, pageable);
        return PageResponse.from(page, page.map(mapper::toMediaResponse).getContent());
    }

    public MediaResponse get(String mediaId, boolean includeHidden) {
        Media media = require(mediaId);
        if (!includeHidden && !hasActiveTarget(media)) {
            throw new ResourceNotFoundException("media not found: " + mediaId);
        }
        return mapper.toMediaResponse(media);
    }

    public MediaResponse create(MediaCreateRequest request) {
        MediaResponse response = mapper.toMediaResponse(createEntity(request));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
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
        MediaResponse response = mapper.toMediaResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
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
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
    }

    private Media require(String mediaId) {
        return repo.findByMediaId(mediaId)
                .orElseThrow(() -> new ResourceNotFoundException("media not found: " + mediaId));
    }

    private Product requireProduct(String productId) {
        return productRepo.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("product not found: " + productId));
    }

    private Variant requireVariant(String variantId) {
        return variantRepo.findByVariantId(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("variant not found: " + variantId));
    }

    private static boolean hasActiveTarget(Media media) {
        Product product = media.getProduct();
        if (product == null && media.getVariant() != null) {
            product = media.getVariant().getProduct();
        }
        return product != null && product.getStatus() == ProductStatus.ACTIVE;
    }
}
