package com.project.dacn;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.CategoryCreateRequest;
import com.project.dacn.dto.request.catalog.CategoryUpdateRequest;
import com.project.dacn.dto.response.catalog.CategoryResponse;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.service.catalog.CategoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    CategoryRepository repo;

    @Mock
    CatalogMapper mapper;

    @InjectMocks
    CategoryService service;

    @Test
    void getMissingThrowsNotFound() {
        when(repo.findByCategoryId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void selfParentRejected() {
        Category c = Category.builder().categoryId("a").name("A").slug("a").build();
        when(repo.findByCategoryId("a")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.update("a", new CategoryUpdateRequest(null, null, "a", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteSetsFlagNotRow() {
        Category c = Category.builder().categoryId("a").name("A").slug("a").build();
        when(repo.findByCategoryId("a")).thenReturn(Optional.of(c));
        service.delete("a");
        assertThat(c.getDeleted()).isTrue();
        verify(repo, never()).delete(any());
    }

    @Test
    void createWithMissingParentThrowsNotFound() {
        when(repo.findByCategoryId("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(new CategoryCreateRequest("C", "c", "ghost")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listReturnsPageResponse() {
        Category c = Category.builder().categoryId("a").name("A").slug("a").build();
        Page<Category> page = new PageImpl<>(List.of(c), Pageable.unpaged(), 1);
        when(repo.findByDeletedFalse(any(Pageable.class))).thenReturn(page);
        when(mapper.toCategoryResponse(c)).thenReturn(
                new CategoryResponse("a", "A", "a", null, false, null, null));
        PageResponse<CategoryResponse> result = service.list(Pageable.unpaged());
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getTotalPages()).isEqualTo(1);
        verify(repo).findByDeletedFalse(any(Pageable.class));
    }
}
