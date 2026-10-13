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

@Service
@Transactional
@RequiredArgsConstructor
public class AttributeValueService {

    private final AttributeValueRepository repo;
    private final AttributeRepository attributeRepo;
    private final CatalogMapper mapper;

    public PageResponse<AttributeValueResponse> list(Pageable pageable) {
        Page<AttributeValue> page = repo.findAll(pageable);
        return PageResponse.from(page, page.map(mapper::toAttributeValueResponse).getContent());
    }

    public PageResponse<AttributeValueResponse> listByAttribute(String attributeId, Pageable pageable) {
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
        return mapper.toAttributeValueResponse(repo.save(entity));
    }

    public AttributeValueResponse update(String attributeValueId, AttributeValueUpdateRequest request) {
        AttributeValue entity = require(attributeValueId);
        mapper.updateAttributeValue(request, entity);
        return mapper.toAttributeValueResponse(repo.save(entity));
    }

    public void delete(String attributeValueId) {
        repo.delete(require(attributeValueId));
    }

    private AttributeValue require(String attributeValueId) {
        return repo.findByAttributeValueId(attributeValueId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("attribute value not found: " + attributeValueId));
    }
}
