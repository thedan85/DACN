package com.project.dacn.service.catalog;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductAttributeValueCreateRequest;
import com.project.dacn.dto.response.catalog.ProductAttributeValueResponse;
import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.ProductAttributeValue;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.AttributeRepository;
import com.project.dacn.repository.catalog.AttributeValueRepository;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ProductAttributeService {

    private final ProductAttributeValueRepository repo;
    private final ProductRepository productRepo;
    private final AttributeRepository attributeRepo;
    private final AttributeValueRepository valueRepo;
    private final CatalogMapper mapper;
    private final CatalogCache cache;

    public PageResponse<ProductAttributeValueResponse> listByProduct(String productId, Pageable pageable) {
        if (productRepo.findByProductId(productId).isEmpty()) {
            throw new ResourceNotFoundException("product not found: " + productId);
        }
        Page<ProductAttributeValue> page = repo.findByProduct_ProductId(productId, pageable);
        return PageResponse.from(page, page.map(mapper::toProductAttributeValueResponse).getContent());
    }

    public ProductAttributeValueResponse get(String productValueId) {
        return mapper.toProductAttributeValueResponse(repo.findByProductValueId(productValueId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("product link not found: " + productValueId)));
    }

    public ProductAttributeValueResponse create(ProductAttributeValueCreateRequest request) {
        Product product = productRepo.findByProductId(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "product not found: " + request.productId()));
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
        ProductAttributeValue entity = mapper.toProductAttributeValue(request);
        entity.setProduct(product);
        entity.setAttribute(attribute);
        entity.setAttributeValue(value);
        ProductAttributeValueResponse response = mapper.toProductAttributeValueResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    public void delete(String productValueId) {
        repo.delete(repo.findByProductValueId(productValueId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("product link not found: " + productValueId)));
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
    }
}
