package com.project.dacn;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.service.catalog.ProductService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    ProductRepository repo;

    @Mock
    CategoryRepository categoryRepo;

    @Mock
    CatalogMapper mapper;

    @InjectMocks
    ProductService service;

    @Test
    void deleteSetsStatusDeleted() {
        Product p = Product.builder().productId("p").name("P").description("d").build();
        when(repo.findByProductId("p")).thenReturn(Optional.of(p));
        service.delete("p");
        assertThat(p.getStatus()).isEqualTo(ProductStatus.DELETED);
    }

    @Test
    void createWithMissingCategoryThrowsNotFound() {
        when(categoryRepo.findByCategoryId("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(
                new ProductCreateRequest("P", "d", null, null, "gone")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMissingThrowsNotFound() {
        when(repo.findByProductId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listReturnsPageResponse() {
        Product p = Product.builder().productId("p").name("P").description("d").build();
        Page<Product> page = new PageImpl<>(List.of(p), Pageable.unpaged(), 1);
        when(repo.findByStatusNot(any(ProductStatus.class), any(Pageable.class))).thenReturn(page);
        when(mapper.toProductResponse(p)).thenReturn(
                new ProductResponse("p", "P", "d", null, null, null, null, null, null, null));
        PageResponse<ProductResponse> result = service.list(Pageable.unpaged());
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(repo).findByStatusNot(any(ProductStatus.class), any(Pageable.class));
    }
}
