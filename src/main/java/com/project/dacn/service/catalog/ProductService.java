package com.project.dacn.service.catalog;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.DuplicateResourceException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.common.util.BusinessId;
import com.project.dacn.common.util.ProductNames;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.request.catalog.ProductUpdateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.spec.ProductFilter;
import com.project.dacn.spec.ProductSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository repo;
    private final CategoryRepository categoryRepo;
    private final CatalogMapper mapper;

    public PageResponse<ProductResponse> list(Pageable pageable) {
        Page<Product> page = repo.findByStatusNot(ProductStatus.DELETED, pageable);
        return PageResponse.from(page, page.map(mapper::toProductResponse).getContent());
    }

    public PageResponse<ProductResponse> search(ProductFilter filter, Pageable pageable) {
        // DELETED is hidden by @SQLRestriction on Product; route explicit DELETED
        // requests through the native admin query that bypasses that restriction.
        if (filter.status() == ProductStatus.DELETED) {
            Page<Product> page = repo.findByStatusIncludingDeleted(
                    ProductStatus.DELETED.name(), filter.name(), filter.categoryId(), pageable);
            return PageResponse.from(page, page.map(mapper::toProductResponse).getContent());
        }
        Page<Product> page = repo.findAll(ProductSpecs.filter(filter), pageable);
        return PageResponse.from(page, page.map(mapper::toProductResponse).getContent());
    }

    public ProductResponse get(String productId) {
        return mapper.toProductResponse(require(productId));
    }

    public ProductResponse create(ProductCreateRequest request) {
        String name = ProductNames.normalize(request.name());
        assertNameAvailable(name, null);

        // Resolve the category before mapping so a bad categoryId fails with 404
        // before any entity state is built.
        Category category = request.categoryId() == null ? null
                : categoryRepo.findByCategoryId(request.categoryId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "category not found: " + request.categoryId()));

        Product entity = mapper.toProduct(request);
        entity.setName(name);
        entity.setCategory(category);
        return mapper.toProductResponse(repo.save(entity));
    }

    public ProductResponse update(String productId, ProductUpdateRequest request) {
        Product entity = require(productId);

        String name = request.name() == null ? null : ProductNames.normalize(request.name());
        if (name != null && !name.equals(entity.getName())) {
            assertNameAvailable(name, entity.getProductId());
        }

        mapper.updateProduct(request, entity);
        if (name != null) {
            entity.setName(name);
        }
        if (request.categoryId() != null) {
            entity.setCategory(categoryRepo.findByCategoryId(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "category not found: " + request.categoryId())));
        }
        // PATCH merge: null categoryId means "not provided", keep existing.
        return mapper.toProductResponse(repo.save(entity));
    }

    /**
     * PUT replace semantics: {@code null} categoryId explicitly detaches the
     * product from its category. Scalar nulls still merge (columns are NOT NULL,
     * so true null-wipe is impossible with the current DTO).
     */
    public ProductResponse replace(String productId, ProductUpdateRequest request) {
        Product entity = require(productId);

        String name = request.name() == null ? null : ProductNames.normalize(request.name());
        if (name != null && !name.equals(entity.getName())) {
            assertNameAvailable(name, entity.getProductId());
        }

        mapper.updateProduct(request, entity);
        if (name != null) {
            entity.setName(name);
        }
        if (request.categoryId() != null) {
            entity.setCategory(categoryRepo.findByCategoryId(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "category not found: " + request.categoryId())));
        } else {
            entity.setCategory(null);
        }
        return mapper.toProductResponse(repo.save(entity));
    }

    public void delete(String productId) {
        Product entity = require(productId);
        entity.setStatus(ProductStatus.DELETED);
        repo.save(entity);
    }

    private Product require(String productId) {
        return repo.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("product not found: " + productId));
    }

    /**
     * Matches by normalized name and by both ID shapes: {@code PR_<slug>}
     * (API-created) and {@code <slug>} (V6/V7 seeds). Native query so
     * soft-deleted rows still reserve the name; flush first so pending
     * writes in the same transaction are visible to it.
     */
    private void assertNameAvailable(String name, String selfId) {
        String slug = BusinessId.slug(name);
        repo.flush();
        if (repo.countNameTaken(name, slug, "PR_" + slug,
                selfId == null ? "" : selfId) > 0) {
            throw new DuplicateResourceException("product name already exists: " + name);
        }
    }
}
