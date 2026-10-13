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
        if (request.parentCategoryId() != null && request.parentCategoryId().equals(categoryId)) {
            throw new IllegalArgumentException("category cannot be its own parent");
        }
        mapper.updateCategory(request, entity);
        if (request.parentCategoryId() != null) {
            entity.setParent(require(request.parentCategoryId()));
        }
        return mapper.toCategoryResponse(repo.save(entity));
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
