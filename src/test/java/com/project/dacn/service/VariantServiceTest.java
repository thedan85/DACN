package com.project.dacn.service;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.VariantCreateRequest;
import com.project.dacn.dto.response.catalog.VariantResponse;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.VariantService;
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
class VariantServiceTest {

    @Mock
    VariantRepository repo;

    @Mock
    ProductRepository productRepo;

    @Mock
    CatalogMapper mapper;

    @InjectMocks
    VariantService service;

    @Test
    void createRequiresExistingProduct() {
        when(productRepo.findByProductId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(
                new VariantCreateRequest("nope", "SKU-1", new BigDecimal("10.00"), 5)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listByProductDelegatesToFinder() {
        Variant v = Variant.builder().variantId("v").sku("SKU-1").build();
        Page<Variant> page = new PageImpl<>(List.of(v), Pageable.unpaged(), 1);
        when(productRepo.findBySlugIgnoreCase("congo-tetra"))
                .thenReturn(Optional.of(com.project.dacn.entity.catalog.Product.builder()
                        .productId("p-1").slug("congo-tetra").name("P").description("d").build()));
        when(repo.findByProduct_SlugIgnoreCase("congo-tetra", Pageable.unpaged())).thenReturn(page);
        when(mapper.toVariantResponse(v)).thenReturn(
                new VariantResponse("v", "SKU-1", new BigDecimal("10.00"), 5, "p-1", "P", false, null, null));
        PageResponse<VariantResponse> result = service.listByProductSlug("congo-tetra", Pageable.unpaged());
        assertThat(result.getContent()).hasSize(1);
        verify(repo).findByProduct_SlugIgnoreCase("congo-tetra", Pageable.unpaged());
    }

    @Test
    void deleteSetsFlagNotRow() {
        Variant v = Variant.builder().variantId("v").sku("SKU-1").build();
        when(repo.findByVariantId("v")).thenReturn(Optional.of(v));
        service.delete("v");
        assertThat(v.getDeleted()).isTrue();
        verify(repo, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void getMissingThrowsNotFound() {
        when(repo.findByVariantId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listByProductMissingParentThrowsNotFound() {
        when(productRepo.findBySlugIgnoreCase("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listByProductSlug("nope", Pageable.unpaged()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
