package com.project.dacn.service;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.VariantCreateRequest;
import com.project.dacn.dto.response.catalog.VariantResponse;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.VariantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VariantServiceTest {

    @Mock
    VariantRepository repo;

    @Mock
    ProductRepository productRepo;

    @Mock
    CatalogMapper mapper;

    @Mock
    CatalogCache cache;

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
                new VariantResponse(1L, "v", "SKU-1", new BigDecimal("10.00"), 5, "p-1", "P", false, null, null));
        PageResponse<VariantResponse> result =
                service.listByProductSlug("congo-tetra", Pageable.unpaged());
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

    @Test
    void listByProductHidesVariantsOfDraftProduct() {
        com.project.dacn.entity.catalog.Product draft =
                com.project.dacn.entity.catalog.Product.builder()
                        .productId("p-1").slug("congo-tetra").name("P").description("d")
                        .status(com.project.dacn.common.constant.ProductStatus.DRAFT).build();
        when(productRepo.findBySlugIgnoreCase("congo-tetra")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.listByProductSlug("congo-tetra", Pageable.unpaged()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cachedVariantWithMissingStockRowFallsBackToTheDatabase() {
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.of(4));
        when(cache.get(eq("pim:variant:4:v"), any())).thenReturn(Optional.of(
                new VariantResponse(1L, "v", "SKU-1", new BigDecimal("10.00"), null,
                        "p-1", "P", false, null, null)));
        when(repo.findStockByVariantId("v")).thenReturn(Optional.empty());
        when(repo.findByVariantId("v")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("v"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repo).findByVariantId("v");
    }

    @Test
    void adminReadNeverTouchesTheCache() {
        Variant v = Variant.builder().variantId("v").sku("SKU-1")
                .product(com.project.dacn.entity.catalog.Product.builder()
                        .productId("p-1").slug("p").name("P").description("d")
                        .status(com.project.dacn.common.constant.ProductStatus.DRAFT).build())
                .build();
        when(repo.findByVariantId("v")).thenReturn(Optional.of(v));
        when(mapper.toVariantResponse(v)).thenReturn(
                new VariantResponse(1L, "v", "SKU-1", new BigDecimal("10.00"), 5, "p-1", "P",
                        false, null, null));

        service.get("v", true);

        verifyNoInteractions(cache);
    }

    @Test
    void getVariantOfDraftProductHiddenByDefault() {
        Variant v = Variant.builder().variantId("v").sku("SKU-1")
                .product(com.project.dacn.entity.catalog.Product.builder()
                        .productId("p-1").slug("p").name("P").description("d")
                        .status(com.project.dacn.common.constant.ProductStatus.DRAFT).build())
                .build();
        when(repo.findByVariantId("v")).thenReturn(Optional.of(v));
        when(mapper.toVariantResponse(v)).thenReturn(
                new VariantResponse(1L, "v", "SKU-1", new BigDecimal("10.00"), 5, "p-1", "P",
                        false, null, null));

        assertThatThrownBy(() -> service.get("v"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.get("v", true).variantId()).isEqualTo("v");
    }

    @Test
    void getCacheHitOverlaysStockAndSkipsRepository() {
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.of(4));
        when(cache.get(eq("pim:variant:4:v"), any())).thenReturn(Optional.of(
                new VariantResponse(1L, "v", "SKU-1", new BigDecimal("10.00"), null,
                        "p-1", "P", false, null, null)));
        VariantRepository.VariantStockView stock = mock(VariantRepository.VariantStockView.class);
        when(stock.getStock()).thenReturn(7);
        when(repo.findStockByVariantId("v")).thenReturn(Optional.of(stock));

        VariantResponse result = service.get("v");

        assertThat(result.stock()).isEqualTo(7);
        verify(repo, never()).findByVariantId(any());
    }

    @Test
    void listByProductCacheHitUsesLowercaseSlugAndSkipsRepositories() {
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.of(4));
        when(cache.get(eq("pim:variant:by-product:congo-tetra:4:0:20:none"), any()))
                .thenReturn(Optional.of(new PageResponse<>(List.of(
                        new VariantResponse(1L, "v", "SKU-1", new BigDecimal("10.00"), null,
                                "p-1", "P", false, null, null)), 0, 20, 1, 1)));
        VariantRepository.VariantStockView stock = mock(VariantRepository.VariantStockView.class);
        when(stock.getVariantId()).thenReturn("v");
        when(stock.getStock()).thenReturn(2);
        when(repo.findStockByVariantIdIn(List.of("v"))).thenReturn(List.of(stock));

        PageResponse<VariantResponse> result =
                service.listByProductSlug("Congo-Tetra", PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).stock()).isEqualTo(2);
        verifyNoInteractions(productRepo);
        verify(repo, never()).findByProduct_SlugIgnoreCase(any(), any());
    }

    @Test
    void listMissStoresScrubbedPage() {
        Variant v = Variant.builder().variantId("v").sku("SKU-1").build();
        Page<Variant> page = new PageImpl<>(List.of(v), PageRequest.of(0, 20), 1);
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.of(4));
        when(cache.get(startsWith("pim:variant:list:4:"), any())).thenReturn(Optional.empty());
        when(repo.findByDeletedFalseAndProduct_Status(
                eq(com.project.dacn.common.constant.ProductStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(page);
        when(mapper.toVariantResponse(v)).thenReturn(new VariantResponse(1L, "v", "SKU-1",
                new BigDecimal("10.00"), 5, "p-1", "P", false, null, null));
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);

        PageResponse<VariantResponse> result = service.list(PageRequest.of(0, 20), false);

        assertThat(result.getContent().get(0).stock()).isEqualTo(5);
        verify(cache).put(startsWith("pim:variant:list:4:"), stored.capture(),
                eq(Duration.ofMinutes(2)));
        assertThat(((PageResponse<VariantResponse>) stored.getValue())
                .getContent().get(0).stock()).isNull();
    }

    @Test
    void getMissStoresScrubbedPayloadAndReturnsLiveStock() {
        Variant v = Variant.builder().variantId("v").sku("SKU-1")
                .product(com.project.dacn.entity.catalog.Product.builder()
                        .productId("p-1").slug("p").name("P").description("d")
                        .status(com.project.dacn.common.constant.ProductStatus.ACTIVE).build())
                .build();
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.of(4));
        when(cache.get(eq("pim:variant:4:v"), any())).thenReturn(Optional.empty());
        when(repo.findByVariantId("v")).thenReturn(Optional.of(v));
        when(mapper.toVariantResponse(v)).thenReturn(
                new VariantResponse(1L, "v", "SKU-1", new BigDecimal("10.00"), 5,
                        "p-1", "P", false, null, null));
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);

        VariantResponse result = service.get("v");

        assertThat(result.stock()).isEqualTo(5);
        verify(cache).put(eq("pim:variant:4:v"), stored.capture(), eq(Duration.ofMinutes(5)));
        assertThat(((VariantResponse) stored.getValue()).stock()).isNull();
    }

    @Test
    void variantWriteBumpsVariantAndProductAfterCommit() {
        when(productRepo.findByProductId("p-1")).thenReturn(Optional.of(
                com.project.dacn.entity.catalog.Product.builder()
                        .productId("p-1").slug("p").name("P").description("d").build()));
        when(mapper.toVariant(any())).thenReturn(Variant.builder().variantId("v").sku("S").build());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.create(new VariantCreateRequest("p-1", "S-1", new BigDecimal("10.00"), 1));

        verify(cache).bumpAfterCommit("variant", "product");
    }

    @Test
    void generationIsReadBeforeTheRepository() {
        Variant v = Variant.builder().variantId("v").sku("SKU-1")
                .product(com.project.dacn.entity.catalog.Product.builder()
                        .productId("p-1").slug("p").name("P").description("d")
                        .status(com.project.dacn.common.constant.ProductStatus.ACTIVE).build())
                .build();
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.empty());
        when(repo.findByVariantId("v")).thenReturn(Optional.of(v));
        when(mapper.toVariantResponse(v)).thenReturn(
                new VariantResponse(1L, "v", "SKU-1", new BigDecimal("10.00"), 5,
                        "p-1", "P", false, null, null));

        service.get("v");

        InOrder order = inOrder(cache, repo);
        order.verify(cache).currentGeneration("variant");
        order.verify(repo).findByVariantId("v");
    }
}
