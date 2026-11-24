package com.project.dacn.service.catalog;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.AttributeValueCreateRequest;
import com.project.dacn.dto.request.catalog.AttributeValueUpdateRequest;
import com.project.dacn.dto.response.catalog.AttributeValueResponse;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.AttributeRepository;
import com.project.dacn.repository.catalog.AttributeValueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.util.Optional;
import java.util.OptionalLong;

@Service
@Transactional
@RequiredArgsConstructor
public class AttributeValueService {

    private static final TypeReference<PageResponse<AttributeValueResponse>> VALUE_PAGE =
            new TypeReference<>() {
            };

    private final AttributeValueRepository repo;
    private final AttributeRepository attributeRepo;
    private final CatalogMapper mapper;
    private final CatalogCache cache;

    public PageResponse<AttributeValueResponse> list(Pageable pageable) {
        if (!CatalogCacheKeys.isCacheablePage(pageable)) {
            return listFromDb(pageable);
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.PRODUCT_SCOPE);
        if (generation.isEmpty()) {
            return listFromDb(pageable);
        }
        String key = CatalogCacheKeys.attributeValueList(generation.getAsLong(), pageable);
        Optional<PageResponse<AttributeValueResponse>> cached = cache.get(key, VALUE_PAGE);
        if (cached.isPresent()) {
            return cached.get();
        }
        PageResponse<AttributeValueResponse> response = listFromDb(pageable);
        cache.put(key, response, CatalogCacheKeys.REFERENCE_PAGE_TTL);
        return response;
    }

    private PageResponse<AttributeValueResponse> listFromDb(Pageable pageable) {
        Page<AttributeValue> page = repo.findAll(pageable);
        return PageResponse.from(page, page.map(mapper::toAttributeValueResponse).getContent());
    }

    public PageResponse<AttributeValueResponse> listByAttribute(String attributeId, Pageable pageable) {
        if (!CatalogCacheKeys.isCacheablePage(pageable)) {
            return listByAttributeFromDb(attributeId, pageable);
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.PRODUCT_SCOPE);
        if (generation.isEmpty()) {
            return listByAttributeFromDb(attributeId, pageable);
        }
        String key = CatalogCacheKeys.attributeValueListByAttribute(
                generation.getAsLong(), attributeId, pageable);
        Optional<PageResponse<AttributeValueResponse>> cached = cache.get(key, VALUE_PAGE);
        if (cached.isPresent()) {
            return cached.get();
        }
        PageResponse<AttributeValueResponse> response = listByAttributeFromDb(attributeId, pageable);
        cache.put(key, response, CatalogCacheKeys.REFERENCE_PAGE_TTL);
        return response;
    }

    private PageResponse<AttributeValueResponse> listByAttributeFromDb(String attributeId,
            Pageable pageable) {
        if (attributeRepo.findByAttributeId(attributeId).isEmpty()) {
            throw new ResourceNotFoundException("attribute not found: " + attributeId);
        }
        Page<AttributeValue> page = repo.findByAttribute_AttributeId(attributeId, pageable);
        return PageResponse.from(page, page.map(mapper::toAttributeValueResponse).getContent());
    }

    public AttributeValueResponse get(String attributeValueId) {
        return mapper.toAttributeValueResponse(require(attributeValueId));
    }

    public AttributeValueResponse create(AttributeValueCreateRequest request) {
        AttributeValue entity = mapper.toAttributeValue(request);
        entity.setAttribute(attributeRepo.findByAttributeId(request.attributeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "attribute not found: " + request.attributeId())));
        AttributeValueResponse response = mapper.toAttributeValueResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    public AttributeValueResponse update(String attributeValueId, AttributeValueUpdateRequest request) {
        AttributeValue entity = require(attributeValueId);
        mapper.updateAttributeValue(request, entity);
        AttributeValueResponse response = mapper.toAttributeValueResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    /**
     * PUT replace. Currently merge-only: kept separate from {@link #update}
     * so PUT/PATCH semantics can diverge.
     */
    public AttributeValueResponse replace(String attributeValueId, AttributeValueUpdateRequest request) {
        return update(attributeValueId, request);
    }

    public void delete(String attributeValueId) {
        repo.delete(require(attributeValueId));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
    }

    private AttributeValue require(String attributeValueId) {
        return repo.findByAttributeValueId(attributeValueId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("attribute value not found: " + attributeValueId));
    }
}
