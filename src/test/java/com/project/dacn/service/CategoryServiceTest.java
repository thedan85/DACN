package com.project.dacn.service;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.CategoryCreateRequest;
import com.project.dacn.dto.request.catalog.CategoryUpdateRequest;
import com.project.dacn.dto.response.catalog.CategoryResponse;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.CategoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    CategoryRepository repo;

    @Mock
    CatalogMapper mapper;

    @Mock
    CatalogCache cache;

    @InjectMocks
    CategoryService service;

    @Test
    void getCacheHitSkipsRepository() {
        CategoryResponse cached = new CategoryResponse("a", "A", "a", null, false, null, null);
        when(cache.currentGeneration("category")).thenReturn(OptionalLong.of(2));
        when(cache.get(eq("pim:category:2:a"), any())).thenReturn(Optional.of(cached));

        assertThat(service.get("a")).isEqualTo(cached);
        verify(repo, never()).findByCategoryId(any());
    }

    @Test
    void getMissStoresWithDetailTtl() {
        Category c = Category.builder().categoryId("a").name("A").slug("a").build();
        CategoryResponse response = new CategoryResponse("a", "A", "a", null, false, null, null);
        when(cache.currentGeneration("category")).thenReturn(OptionalLong.of(2));
        when(cache.get(eq("pim:category:2:a"), any())).thenReturn(Optional.empty());
        when(repo.findByCategoryId("a")).thenReturn(Optional.of(c));
        when(mapper.toCategoryResponse(c)).thenReturn(response);

        assertThat(service.get("a")).isEqualTo(response);
        verify(cache).put("pim:category:2:a", response, Duration.ofMinutes(10));
    }

    @Test
    void categoryWriteBumpsCategoryAndProductAfterCommit() {
        Category c = Category.builder().categoryId("a").name("A").slug("a").build();
        when(repo.findByCategoryId("a")).thenReturn(Optional.of(c));

        service.delete("a");

        verify(cache).bumpAfterCommit("category", "product");
    }

    @Test
    void generationIsReadBeforeTheRepository() {
        Category c = Category.builder().categoryId("a").name("A").slug("a").build();
        when(cache.currentGeneration("category")).thenReturn(OptionalLong.empty());
        when(repo.findByCategoryId("a")).thenReturn(Optional.of(c));
        when(mapper.toCategoryResponse(c)).thenReturn(
                new CategoryResponse("a", "A", "a", null, false, null, null));

        service.get("a");

        InOrder order = inOrder(cache, repo);
        order.verify(cache).currentGeneration("category");
        order.verify(repo).findByCategoryId("a");
    }

    @Test
    void getMissingThrowsNotFound() {
        when(repo.findByCategoryId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void selfParentRejected() {
        Category c = Category.builder().categoryId("a").name("A").slug("a").build();
        when(repo.findByCategoryId("a")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.update("a", new CategoryUpdateRequest(null, null, "a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transitiveCycleRejected() {
        Category a = Category.builder().categoryId("a").name("A").slug("a").build();
        Category b = Category.builder().categoryId("b").name("B").slug("b").build();
        Category c = Category.builder().categoryId("c").name("C").slug("c").build();
        b.setParent(a);
        c.setParent(b);
        when(repo.findByCategoryId("a")).thenReturn(Optional.of(a));
        when(repo.findByCategoryId("c")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.update("a", new CategoryUpdateRequest(null, null, "c")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void replaceWithNullParentDetachesToRoot() {
        Category child = Category.builder().categoryId("child").name("C").slug("c").build();
        child.setParent(Category.builder().categoryId("parent").name("P").slug("p").build());
        when(repo.findByCategoryId("child")).thenReturn(Optional.of(child));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        service.replace("child", new CategoryUpdateRequest(null, null, null));
        assertThat(child.getParent()).isNull();
    }

    @Test
    void patchWithNullParentKeepsExisting() {
        Category parent = Category.builder().categoryId("parent").name("P").slug("p").build();
        Category child = Category.builder().categoryId("child").name("C").slug("c").build();
        child.setParent(parent);
        when(repo.findByCategoryId("child")).thenReturn(Optional.of(child));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        service.update("child", new CategoryUpdateRequest(null, null, null));
        assertThat(child.getParent()).isSameAs(parent);
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
