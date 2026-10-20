package com.project.dacn.service;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.request.catalog.ProductUpdateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.MediaRepository;
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
    MediaRepository mediaRepo;

    @Mock
    CatalogMapper mapper;

    @InjectMocks
    ProductService service;

    @Test
    void deleteSetsStatusDeleted() {
        Product p = Product.builder().productId("p").slug("p").name("P").description("d").build();
        when(repo.findByProductId("p")).thenReturn(Optional.of(p));
        service.delete("p");
        assertThat(p.getStatus()).isEqualTo(ProductStatus.DELETED);
    }

    @Test
    void createWithMissingCategoryThrowsNotFound() {
        when(categoryRepo.findByCategoryId("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(
                new ProductCreateRequest("P", "d", null, "gone", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMissingThrowsNotFound() {
        when(repo.findBySlugIgnoreCase("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getBySlug("nope")).isInstanceOf(ResourceNotFoundException.class);
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
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of(range));
        when(mapper.toProductResponse(p, range.minPrice(), range.maxPrice()))
                .thenReturn(response(range.minPrice(), range.maxPrice()));

        ProductResponse result = service.getBySlug("ember-tetra");

        assertThat(result.minPrice()).isEqualByComparingTo("179000");
        assertThat(result.maxPrice()).isEqualByComparingTo("199000");
    }

    @Test
    void updateAssignsOwnedMediaAsThumbnail() {
        Product p = product(1L);
        Media media = Media.builder().mediaId("MED_1").product(p).build();
        when(repo.findByProductId("PRD_1")).thenReturn(Optional.of(p));
        when(mediaRepo.findByMediaId("MED_1")).thenReturn(Optional.of(media));
        when(repo.save(p)).thenReturn(p);
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());

        service.update("PRD_1", new ProductUpdateRequest(null, null, null, "MED_1", null, null));

        assertThat(p.getThumbnail()).isSameAs(media);
    }

    @Test
    void updateRejectsMediaOwnedByAnotherProduct() {
        Product p = product(1L);
        Media foreign = Media.builder().mediaId("MED_x").product(product(2L)).build();
        when(repo.findByProductId("PRD_1")).thenReturn(Optional.of(p));
        when(mediaRepo.findByMediaId("MED_x")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.update("PRD_1",
                new ProductUpdateRequest(null, null, null, "MED_x", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateRejectsUnknownMediaId() {
        Product p = product(1L);
        when(repo.findByProductId("PRD_1")).thenReturn(Optional.of(p));
        when(mediaRepo.findByMediaId("MED_nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("PRD_1",
                new ProductUpdateRequest(null, null, null, "MED_nope", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static Product product(Long id) {
        return Product.builder()
                .id(id)
                .productId("PRD_" + id)
                .slug("ember-tetra")
                .name("P")
                .description("d")
                .build();
    }

    private static ProductResponse response(BigDecimal minPrice, BigDecimal maxPrice) {
        return new ProductResponse("PRD_1", "ember-tetra", "P", "d", null, null, null,
                ProductStatus.ACTIVE, null, null, minPrice, maxPrice, null, null);
    }
}
