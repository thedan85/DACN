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

@Service
@Transactional
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository repo;
    private final CatalogMapper mapper;

    public PageResponse<CategoryResponse> list(Pageable pageable) {
        Page<Category> page = repo.findByDeletedFalse(pageable);
        return PageResponse.from(page, page.map(mapper::toCategoryResponse).getContent());
    }

    public CategoryResponse get(String categoryId) {
        return mapper.toCategoryResponse(require(categoryId));
    }

    public CategoryResponse create(CategoryCreateRequest request) {
        Category entity = mapper.toCategory(request);
        if (request.parentCategoryId() != null) {
            entity.setParent(require(request.parentCategoryId()));
        }
        return mapper.toCategoryResponse(repo.save(entity));
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
        return mapper.toCategoryResponse(repo.save(entity));
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
        return mapper.toCategoryResponse(repo.save(entity));
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
    }

    private Category require(String categoryId) {
        return repo.findByCategoryId(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("category not found: " + categoryId));
    }
}
