package com.project.dacn.service;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.ProductPriceRange;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
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
    VariantRepository variantRepo;

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
    void listReturnsPageResponseWithoutPricesWhenProductHasNoVariants() {
        Product p = product(1L);
        Page<Product> page = new PageImpl<>(List.of(p), Pageable.unpaged(), 1);
        when(repo.findByStatusNot(any(ProductStatus.class), any(Pageable.class))).thenReturn(page);
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());
        when(mapper.toProductResponse(p, null, null)).thenReturn(response(null, null));

        PageResponse<ProductResponse> result = service.list(Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).minPrice()).isNull();
        assertThat(result.getContent().get(0).maxPrice()).isNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(repo).findByStatusNot(any(ProductStatus.class), any(Pageable.class));
    }

    @Test
    void listIncludesMinAndMaxPriceFromVariants() {
        Product p = product(1L);
        Page<Product> page = new PageImpl<>(List.of(p), Pageable.unpaged(), 1);
        ProductPriceRange range = new ProductPriceRange(
                1L, new BigDecimal("179000"), new BigDecimal("199000"));
        when(repo.findByStatusNot(any(ProductStatus.class), any(Pageable.class))).thenReturn(page);
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of(range));
        when(mapper.toProductResponse(p, range.minPrice(), range.maxPrice()))
                .thenReturn(response(range.minPrice(), range.maxPrice()));

        PageResponse<ProductResponse> result = service.list(Pageable.unpaged());

        assertThat(result.getContent().get(0).minPrice()).isEqualByComparingTo("179000");
        assertThat(result.getContent().get(0).maxPrice()).isEqualByComparingTo("199000");
    }

    @Test
    void getIncludesMinAndMaxPriceFromVariants() {
        Product p = product(1L);
        ProductPriceRange range = new ProductPriceRange(
                1L, new BigDecimal("179000"), new BigDecimal("199000"));
        when(repo.findByProductId("PRD_1")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of(range));
        when(mapper.toProductResponse(p, range.minPrice(), range.maxPrice()))
                .thenReturn(response(range.minPrice(), range.maxPrice()));

        ProductResponse result = service.get("PRD_1");

        assertThat(result.minPrice()).isEqualByComparingTo("179000");
        assertThat(result.maxPrice()).isEqualByComparingTo("199000");
    }

    private static Product product(Long id) {
        return Product.builder()
                .id(id)
                .productId("PRD_" + id)
                .name("P")
                .description("d")
                .build();
    }

    private static ProductResponse response(BigDecimal minPrice, BigDecimal maxPrice) {
        return new ProductResponse("PRD_1", "P", "d", null, null,
                ProductStatus.ACTIVE, null, null, minPrice, maxPrice, null, null);
    }
}
