package com.project.dacn.service.catalog;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.AttributeCreateRequest;
import com.project.dacn.dto.request.catalog.AttributeUpdateRequest;
import com.project.dacn.dto.response.catalog.AttributeResponse;
import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.AttributeRepository;
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
public class AttributeService {

    private static final TypeReference<PageResponse<AttributeResponse>> ATTRIBUTE_PAGE =
            new TypeReference<>() {
            };

    private final AttributeRepository repo;
    private final CatalogMapper mapper;
    private final CatalogCache cache;

    public PageResponse<AttributeResponse> list(Pageable pageable) {
        if (!CatalogCacheKeys.isCacheablePage(pageable)) {
            return listFromDb(pageable);
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.PRODUCT_SCOPE);
        if (generation.isEmpty()) {
            return listFromDb(pageable);
        }
        String key = CatalogCacheKeys.attributeList(generation.getAsLong(), pageable);
        Optional<PageResponse<AttributeResponse>> cached = cache.get(key, ATTRIBUTE_PAGE);
        if (cached.isPresent()) {
            return cached.get();
        }
        PageResponse<AttributeResponse> response = listFromDb(pageable);
        cache.put(key, response, CatalogCacheKeys.REFERENCE_PAGE_TTL);
        return response;
    }

    private PageResponse<AttributeResponse> listFromDb(Pageable pageable) {
        Page<Attribute> page = repo.findByDeletedFalse(pageable);
        return PageResponse.from(page, page.map(mapper::toAttributeResponse).getContent());
    }

    public AttributeResponse get(String attributeId) {
        return mapper.toAttributeResponse(require(attributeId));
    }

    public AttributeResponse create(AttributeCreateRequest request) {
        AttributeResponse response = mapper.toAttributeResponse(repo.save(mapper.toAttribute(request)));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    public AttributeResponse update(String attributeId, AttributeUpdateRequest request) {
        Attribute entity = require(attributeId);
        mapper.updateAttribute(request, entity);
        AttributeResponse response = mapper.toAttributeResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    /**
     * PUT replace. Currently merge-only: kept separate from {@link #update}
     * so PUT/PATCH semantics can diverge.
     */
    public AttributeResponse replace(String attributeId, AttributeUpdateRequest request) {
        return update(attributeId, request);
    }

    public void delete(String attributeId) {
        Attribute entity = require(attributeId);
        entity.setDeleted(true);
        repo.save(entity);
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
    }

    private Attribute require(String attributeId) {
        return repo.findByAttributeId(attributeId)
                .orElseThrow(() -> new ResourceNotFoundException("attribute not found: " + attributeId));
    }
}
