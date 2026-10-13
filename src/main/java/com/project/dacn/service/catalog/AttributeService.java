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

@Service
@Transactional
@RequiredArgsConstructor
public class AttributeService {

    private final AttributeRepository repo;
    private final CatalogMapper mapper;

    public PageResponse<AttributeResponse> list(Pageable pageable) {
        Page<Attribute> page = repo.findAll(pageable);
        return PageResponse.from(page, page.map(mapper::toAttributeResponse).getContent());
    }

    public AttributeResponse get(String attributeId) {
        return mapper.toAttributeResponse(require(attributeId));
    }

    public AttributeResponse create(AttributeCreateRequest request) {
        return mapper.toAttributeResponse(repo.save(mapper.toAttribute(request)));
    }

    public AttributeResponse update(String attributeId, AttributeUpdateRequest request) {
        Attribute entity = require(attributeId);
        mapper.updateAttribute(request, entity);
        return mapper.toAttributeResponse(repo.save(entity));
    }

    public void delete(String attributeId) {
        Attribute entity = require(attributeId);
        entity.setDeleted(true);
        repo.save(entity);
    }

    private Attribute require(String attributeId) {
        return repo.findByAttributeId(attributeId)
                .orElseThrow(() -> new ResourceNotFoundException("attribute not found: " + attributeId));
    }
}
