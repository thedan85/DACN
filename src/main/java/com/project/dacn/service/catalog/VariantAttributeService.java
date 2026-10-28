package com.project.dacn.service.catalog;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.VariantAttributeValueCreateRequest;
import com.project.dacn.dto.response.catalog.VariantAttributeValueResponse;
import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.catalog.VariantAttributeValue;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.AttributeRepository;
import com.project.dacn.repository.catalog.AttributeValueRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class VariantAttributeService {

    private final VariantAttributeValueRepository repo;
    private final VariantRepository variantRepo;
    private final AttributeRepository attributeRepo;
    private final AttributeValueRepository valueRepo;
    private final CatalogMapper mapper;
    private final CatalogCache cache;

    public PageResponse<VariantAttributeValueResponse> listByVariant(String variantId, Pageable pageable) {
        if (variantRepo.findByVariantId(variantId).isEmpty()) {
            throw new ResourceNotFoundException("variant not found: " + variantId);
        }
        Page<VariantAttributeValue> page = repo.findByVariant_VariantId(variantId, pageable);
        return PageResponse.from(page, page.map(mapper::toVariantAttributeValueResponse).getContent());
    }

    public VariantAttributeValueResponse get(String variantValueId) {
        return mapper.toVariantAttributeValueResponse(repo.findByVariantValueId(variantValueId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("variant link not found: " + variantValueId)));
    }

    public VariantAttributeValueResponse create(VariantAttributeValueCreateRequest request) {
        Variant variant = variantRepo.findByVariantId(request.variantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "variant not found: " + request.variantId()));
        Attribute attribute = attributeRepo.findByAttributeId(request.attributeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "attribute not found: " + request.attributeId()));
        AttributeValue value = valueRepo.findByAttributeValueId(request.attributeValueId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "attribute value not found: " + request.attributeValueId()));
        if (value.getAttribute() == null
                || !value.getAttribute().getAttributeId().equals(request.attributeId())) {
            throw new IllegalArgumentException("attribute value does not belong to attribute");
        }
        VariantAttributeValue entity = mapper.toVariantAttributeValue(request);
        entity.setVariant(variant);
        entity.setAttribute(attribute);
        entity.setAttributeValue(value);
        VariantAttributeValueResponse response = mapper.toVariantAttributeValueResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    public void delete(String variantValueId) {
        repo.delete(repo.findByVariantValueId(variantValueId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("variant link not found: " + variantValueId)));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
    }
}
