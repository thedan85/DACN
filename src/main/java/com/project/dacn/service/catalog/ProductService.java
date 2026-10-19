package com.project.dacn.service.catalog;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.request.catalog.ProductUpdateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.ProductPriceRange;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.spec.ProductFilter;
import com.project.dacn.spec.ProductSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository repo;
    private final CategoryRepository categoryRepo;
    private final VariantRepository variantRepo;
    private final CatalogMapper mapper;

    public PageResponse<ProductResponse> list(Pageable pageable) {
        Page<Product> page = repo.findByStatusNot(ProductStatus.DELETED, pageable);
        return toPageResponse(page);
    }

    public PageResponse<ProductResponse> search(ProductFilter filter, Pageable pageable) {
        // DELETED is hidden by @SQLRestriction on Product; route explicit DELETED
        // requests through the native admin query that bypasses that restriction.
        if (filter.status() == ProductStatus.DELETED) {
            Page<Product> page = repo.findByStatusIncludingDeleted(
                    ProductStatus.DELETED.name(), filter.name(), filter.categoryId(), pageable);
            return toPageResponse(page);
        }
        Page<Product> page = repo.findAll(ProductSpecs.filter(filter), pageable);
        return toPageResponse(page);
    }

    public ProductResponse get(String productId) {
        return toResponse(require(productId));
    }

    public ProductResponse create(ProductCreateRequest request) {
        // Resolve the category before mapping so a bad categoryId fails with 404
        // before any entity state is built.
        Category category = request.categoryId() == null ? null
                : categoryRepo.findByCategoryId(request.categoryId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "category not found: " + request.categoryId()));

        Product entity = mapper.toProduct(request);
        entity.setName(normalizeName(request.name()));
        entity.setCategory(category);

        // product_id is PRD_<id>, and the identity id only exists after the
        // insert. @PrePersist assigns a throwaway PRD_<hex> so the NOT NULL
        // column is satisfied, then we rewrite it to the readable form.
        entity = repo.saveAndFlush(entity);
        entity.setProductId("PRD_" + entity.getId());
        // A freshly created product has no variants yet, so skip the price
        // lookup here: querying would auto-flush the changed natural id and
        // Hibernate rejects that before the transaction commits.
        return toResponse(repo.save(entity), null);
    }

    public ProductResponse update(String productId, ProductUpdateRequest request) {
        Product entity = require(productId);

        mapper.updateProduct(request, entity);
        if (request.name() != null) {
            entity.setName(normalizeName(request.name()));
        }
        if (request.categoryId() != null) {
            entity.setCategory(categoryRepo.findByCategoryId(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "category not found: " + request.categoryId())));
        }
        // PATCH merge: null categoryId means "not provided", keep existing.
        return toResponse(repo.save(entity));
    }

    /**
     * PUT replace semantics: {@code null} categoryId explicitly detaches the
     * product from its category. Scalar nulls still merge (columns are NOT NULL,
     * so true null-wipe is impossible with the current DTO).
     */
    public ProductResponse replace(String productId, ProductUpdateRequest request) {
        Product entity = require(productId);

        mapper.updateProduct(request, entity);
        if (request.name() != null) {
            entity.setName(normalizeName(request.name()));
        }
        if (request.categoryId() != null) {
            entity.setCategory(categoryRepo.findByCategoryId(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "category not found: " + request.categoryId())));
        } else {
            entity.setCategory(null);
        }
        return toResponse(repo.save(entity));
    }

    public void delete(String productId) {
        Product entity = require(productId);
        entity.setStatus(ProductStatus.DELETED);
        repo.save(entity);
    }

    private PageResponse<ProductResponse> toPageResponse(Page<Product> page) {
        Map<Long, ProductPriceRange> ranges = priceRanges(page.getContent());
        List<ProductResponse> content = page.getContent().stream()
                .map(product -> toResponse(product, ranges.get(product.getId())))
                .toList();
        return PageResponse.from(page, content);
    }

    /**
     * Batch-load min/max variant prices for a whole product page.
     */
    private Map<Long, ProductPriceRange> priceRanges(List<Product> products) {
        List<Long> productIds = products.stream()
                .map(Product::getId)
                .filter(Objects::nonNull)
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return variantRepo.findPriceRangesByProductIds(productIds).stream()
                .collect(Collectors.toMap(ProductPriceRange::productId, Function.identity()));
    }

    private ProductResponse toResponse(Product product) {
        return toResponse(product, priceRange(product.getId()));
    }

    private ProductPriceRange priceRange(Long productId) {
        if (productId == null) {
            return null;
        }
        return variantRepo.findPriceRangesByProductIds(List.of(productId)).stream()
                .findFirst()
                .orElse(null);
    }

    private ProductResponse toResponse(Product product, ProductPriceRange range) {
        return mapper.toProductResponse(product,
                range == null ? null : range.minPrice(),
                range == null ? null : range.maxPrice());
    }

    private Product require(String productId) {
        return repo.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("product not found: " + productId));
    }

    /**
     * Names are display-only (not unique, not used as a key), so the only
     * cleanup is trimming and collapsing internal whitespace.
     */
    private static String normalizeName(String raw) {
        String name = raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("product name must not be blank");
        }
        return name;
    }
}
