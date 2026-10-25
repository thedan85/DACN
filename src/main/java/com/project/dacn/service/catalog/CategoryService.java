package com.project.dacn.service.catalog;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.CategoryCreateRequest;
import com.project.dacn.dto.request.catalog.CategoryUpdateRequest;
import com.project.dacn.dto.response.catalog.CategoryResponse;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
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
public class CategoryService {

    private static final TypeReference<CategoryResponse> CATEGORY_RESPONSE = new TypeReference<>() {
    };
    private static final TypeReference<PageResponse<CategoryResponse>> CATEGORY_PAGE =
            new TypeReference<>() {
            };

    private final CategoryRepository repo;
    private final CatalogMapper mapper;
    private final CatalogCache cache;

    public PageResponse<CategoryResponse> list(Pageable pageable) {
        if (!CatalogCacheKeys.isCacheablePage(pageable)) {
            return listFromDb(pageable);
        }
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.CATEGORY_SCOPE);
        if (generation.isEmpty()) {
            return listFromDb(pageable);
        }
        String key = CatalogCacheKeys.categoryList(generation.getAsLong(), pageable);
        Optional<PageResponse<CategoryResponse>> cached = cache.get(key, CATEGORY_PAGE);
        if (cached.isPresent()) {
            return cached.get();
        }
        PageResponse<CategoryResponse> response = listFromDb(pageable);
        cache.put(key, response, CatalogCacheKeys.CATEGORY_PAGE_TTL);
        return response;
    }

    private PageResponse<CategoryResponse> listFromDb(Pageable pageable) {
        Page<Category> page = repo.findByDeletedFalse(pageable);
        return PageResponse.from(page, page.map(mapper::toCategoryResponse).getContent());
    }

    public CategoryResponse get(String categoryId) {
        OptionalLong generation = cache.currentGeneration(CatalogCacheKeys.CATEGORY_SCOPE);
        if (generation.isEmpty()) {
            return mapper.toCategoryResponse(require(categoryId));
        }
        String key = CatalogCacheKeys.categoryDetail(generation.getAsLong(), categoryId);
        Optional<CategoryResponse> cached = cache.get(key, CATEGORY_RESPONSE);
        if (cached.isPresent()) {
            return cached.get();
        }
        CategoryResponse response = mapper.toCategoryResponse(require(categoryId));
        cache.put(key, response, CatalogCacheKeys.CATEGORY_DETAIL_TTL);
        return response;
    }

    public CategoryResponse create(CategoryCreateRequest request) {
        Category entity = mapper.toCategory(request);
        if (request.parentCategoryId() != null) {
            entity.setParent(require(request.parentCategoryId()));
        }
        CategoryResponse response = mapper.toCategoryResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.CATEGORY_SCOPE, CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    public CategoryResponse update(String categoryId, CategoryUpdateRequest request) {
        Category entity = require(categoryId);
        if (request.parentCategoryId() != null) {
            if (request.parentCategoryId().equals(categoryId)) {
                throw new IllegalArgumentException("category cannot be its own parent");
            }
            Category newParent = require(request.parentCategoryId());
            assertNoCycle(categoryId, newParent);
            mapper.updateCategory(request, entity);
            entity.setParent(newParent);
        } else {
            // PATCH merge semantics: null means "not provided", keep existing parent.
            mapper.updateCategory(request, entity);
        }
        CategoryResponse response = mapper.toCategoryResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.CATEGORY_SCOPE, CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    /**
     * PUT replace semantics: {@code null} parentCategoryId explicitly detaches
     * the category to root. Scalar nulls still merge (name/slug are NOT NULL
     * in DB, so true null-wipe is impossible with the current DTO).
     */
    public CategoryResponse replace(String categoryId, CategoryUpdateRequest request) {
        Category entity = require(categoryId);
        if (request.parentCategoryId() != null) {
            if (request.parentCategoryId().equals(categoryId)) {
                throw new IllegalArgumentException("category cannot be its own parent");
            }
            Category newParent = require(request.parentCategoryId());
            assertNoCycle(categoryId, newParent);
            mapper.updateCategory(request, entity);
            entity.setParent(newParent);
        } else {
            mapper.updateCategory(request, entity);
            entity.setParent(null);
        }
        CategoryResponse response = mapper.toCategoryResponse(repo.save(entity));
        cache.bumpAfterCommit(CatalogCacheKeys.CATEGORY_SCOPE, CatalogCacheKeys.PRODUCT_SCOPE);
        return response;
    }

    private void assertNoCycle(String categoryId, Category newParent) {
        Category cursor = newParent;
        while (cursor != null) {
            if (categoryId.equals(cursor.getCategoryId())) {
                throw new IllegalArgumentException("category hierarchy cycle detected");
            }
            cursor = cursor.getParent();
        }
    }

    public void delete(String categoryId) {
        Category entity = require(categoryId);
        entity.setDeleted(true);
        repo.save(entity);
        cache.bumpAfterCommit(CatalogCacheKeys.CATEGORY_SCOPE, CatalogCacheKeys.PRODUCT_SCOPE);
    }

    private Category require(String categoryId) {
        return repo.findByCategoryId(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("category not found: " + categoryId));
    }
}
