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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VariantServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void injectPersistenceContext() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    @Mock
    VariantRepository repo;

    @Mock
    ProductRepository productRepo;

    @Mock
    CatalogMapper mapper;

    @Mock
    CatalogCache cache;

    @Mock
    jakarta.persistence.EntityManager entityManager;

    @InjectMocks
    VariantService service;

    @Test
    void explicitStockRequiresRevisionBeforeAnyMutation() {
        Variant v = Variant.builder().variantId("v").sku("old").stock(10).build();
        org.mockito.Mockito.lenient().when(repo.findByVariantId("v")).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.update("v",
                new com.project.dacn.dto.request.catalog.VariantUpdateRequest("new", BigDecimal.ONE, 7, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expectedStockRevision is required when stock is supplied");
        assertThat(v.getSku()).isEqualTo("old");
        verify(mapper, never()).updateVariant(any(), any());
        verify(cache, never()).bumpAfterCommit(any());
    }

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
                new VariantResponse("v", "SKU-1", new BigDecimal("10.00"), 5, 0L, "p-1", "P", false, null, null));
        PageResponse<VariantResponse> result =
                service.listByProductSlug("congo-tetra", Pageable.unpaged());
        assertThat(result.getContent()).hasSize(1);
        verify(repo).findByProduct_SlugIgnoreCase("congo-tetra", Pageable.unpaged());
    }

    @Test
    void deleteSetsFlagNotRow() {
        Variant v = Variant.builder().variantId("v").sku("SKU-1").build();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionLockBounds", false);
        when(repo.findByVariantIdForUpdate("v")).thenReturn(Optional.of(v));
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
                new VariantResponse("v", "SKU-1", new BigDecimal("10.00"), null, null,
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
                new VariantResponse("v", "SKU-1", new BigDecimal("10.00"), 5, 0L, "p-1", "P",
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
                new VariantResponse("v", "SKU-1", new BigDecimal("10.00"), 5, 0L, "p-1", "P",
                        false, null, null));

        assertThatThrownBy(() -> service.get("v"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.get("v", true).variantId()).isEqualTo("v");
    }

    @Test
    void getCacheHitOverlaysStockAndSkipsRepository() {
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.of(4));
        when(cache.get(eq("pim:variant:4:v"), any())).thenReturn(Optional.of(
                new VariantResponse("v", "SKU-1", new BigDecimal("10.00"), null, null,
                        "p-1", "P", false, null, null)));
        VariantRepository.VariantStockView stock = mock(VariantRepository.VariantStockView.class);
        when(stock.getStock()).thenReturn(7);
        when(stock.getStockRevision()).thenReturn(8L);
        when(repo.findStockByVariantId("v")).thenReturn(Optional.of(stock));

        VariantResponse result = service.get("v");

        assertThat(result.stock()).isEqualTo(7);
        assertThat(result.stockRevision()).isEqualTo(8L);
        verify(repo, never()).findByVariantId(any());
    }

    @Test
    void listByProductCacheHitUsesLowercaseSlugAndSkipsRepositories() {
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.of(4));
        when(cache.get(eq("pim:variant:by-product:congo-tetra:4:0:20:none"), any()))
                .thenReturn(Optional.of(new PageResponse<>(List.of(
                        new VariantResponse("v", "SKU-1", new BigDecimal("10.00"), null, null,
                                "p-1", "P", false, null, null)), 0, 20, 1, 1)));
        VariantRepository.VariantStockView stock = mock(VariantRepository.VariantStockView.class);
        when(stock.getVariantId()).thenReturn("v");
        when(stock.getStock()).thenReturn(2);
        when(stock.getStockRevision()).thenReturn(8L);
        when(repo.findStockByVariantIdIn(List.of("v"))).thenReturn(List.of(stock));

        PageResponse<VariantResponse> result =
                service.listByProductSlug("Congo-Tetra", PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).stock()).isEqualTo(2);
        assertThat(result.getContent().get(0).stockRevision()).isEqualTo(8L);
        verify(repo, times(1)).findStockByVariantIdIn(List.of("v"));
        verify(repo, never()).findStockByVariantId(any());
        verifyNoInteractions(productRepo);
        verify(repo, never()).findByProduct_SlugIgnoreCase(any(), any());
    }

    @Test
    void listCacheHitOverlaysStockAndRevisionFromOneBatch() {
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.of(4));
        when(cache.get(startsWith("pim:variant:list:4:"), any())).thenReturn(Optional.of(
                new PageResponse<>(List.of(
                        new VariantResponse("v1", "SKU-1", new BigDecimal("10.00"), null, null,
                                "p-1", "P", false, null, null),
                        new VariantResponse("v2", "SKU-2", new BigDecimal("20.00"), null, null,
                                "p-1", "P", false, null, null)), 0, 20, 2, 1)));
        VariantRepository.VariantStockView first = mock(VariantRepository.VariantStockView.class);
        when(first.getVariantId()).thenReturn("v1");
        when(first.getStock()).thenReturn(2);
        when(first.getStockRevision()).thenReturn(5L);
        VariantRepository.VariantStockView second = mock(VariantRepository.VariantStockView.class);
        when(second.getVariantId()).thenReturn("v2");
        when(second.getStock()).thenReturn(0);
        when(second.getStockRevision()).thenReturn(3L);
        when(repo.findStockByVariantIdIn(List.of("v1", "v2")))
                .thenReturn(List.of(first, second));

        PageResponse<VariantResponse> result = service.list(PageRequest.of(0, 20), false);

        assertThat(result.getContent()).extracting(VariantResponse::stock).containsExactly(2, 0);
        assertThat(result.getContent()).extracting(VariantResponse::stockRevision)
                .containsExactly(5L, 3L);
        verify(repo, times(1)).findStockByVariantIdIn(List.of("v1", "v2"));
        verify(repo, never()).findStockByVariantId(any());
        verify(repo, never()).findByDeletedFalseAndProduct_Status(any(), any());
    }

    @Test
    void listByProductSlugMissStoresScrubbedPageAndReturnsLiveRevision() {
        Variant v = Variant.builder().variantId("v").sku("SKU-1").build();
        Page<Variant> page = new PageImpl<>(List.of(v), PageRequest.of(0, 20), 1);
        when(cache.currentGeneration("variant")).thenReturn(OptionalLong.of(4));
        when(cache.get(eq("pim:variant:by-product:congo-tetra:4:0:20:none"), any()))
                .thenReturn(Optional.empty());
        when(productRepo.findBySlugIgnoreCase("Congo-Tetra"))
                .thenReturn(Optional.of(com.project.dacn.entity.catalog.Product.builder()
                        .productId("p-1").slug("congo-tetra").name("P").description("d")
                        .status(com.project.dacn.common.constant.ProductStatus.ACTIVE).build()));
        when(repo.findByProduct_SlugIgnoreCase("Congo-Tetra", PageRequest.of(0, 20)))
                .thenReturn(page);
        when(mapper.toVariantResponse(v)).thenReturn(new VariantResponse("v", "SKU-1",
                new BigDecimal("10.00"), 5, 2L, "p-1", "P", false, null, null));
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);

        PageResponse<VariantResponse> result =
                service.listByProductSlug("Congo-Tetra", PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).stock()).isEqualTo(5);
        assertThat(result.getContent().get(0).stockRevision()).isEqualTo(2L);
        verify(cache).put(eq("pim:variant:by-product:congo-tetra:4:0:20:none"),
                stored.capture(), eq(Duration.ofMinutes(2)));
        assertThat(((PageResponse<VariantResponse>) stored.getValue())
                .getContent().get(0).stock()).isNull();
        assertThat(((PageResponse<VariantResponse>) stored.getValue())
                .getContent().get(0).stockRevision()).isNull();
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
        when(mapper.toVariantResponse(v)).thenReturn(new VariantResponse("v", "SKU-1",
                new BigDecimal("10.00"), 5, 0L, "p-1", "P", false, null, null));
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);

        PageResponse<VariantResponse> result = service.list(PageRequest.of(0, 20), false);

        assertThat(result.getContent().get(0).stock()).isEqualTo(5);
        verify(cache).put(startsWith("pim:variant:list:4:"), stored.capture(),
                eq(Duration.ofMinutes(2)));
        assertThat(((PageResponse<VariantResponse>) stored.getValue())
                .getContent().get(0).stock()).isNull();
        assertThat(((PageResponse<VariantResponse>) stored.getValue())
                .getContent().get(0).stockRevision()).isNull();
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
                new VariantResponse("v", "SKU-1", new BigDecimal("10.00"), 5, 0L,
                        "p-1", "P", false, null, null));
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);

        VariantResponse result = service.get("v");

        assertThat(result.stock()).isEqualTo(5);
        assertThat(result.stockRevision()).isZero();
        verify(cache).put(eq("pim:variant:4:v"), stored.capture(), eq(Duration.ofMinutes(5)));
        assertThat(((VariantResponse) stored.getValue()).stock()).isNull();
        assertThat(((VariantResponse) stored.getValue()).stockRevision()).isNull();
    }

    @Test
    void variantWriteBumpsVariantAndProductAfterCommit() {
        when(productRepo.findByProductId("p-1")).thenReturn(Optional.of(
                com.project.dacn.entity.catalog.Product.builder()
                        .productId("p-1").slug("p").name("P").description("d").build()));
        when(mapper.toVariant(any())).thenReturn(Variant.builder().variantId("v").sku("S").build());
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
                new VariantResponse("v", "SKU-1", new BigDecimal("10.00"), 5, 0L,
                        "p-1", "P", false, null, null));

        service.get("v");

        InOrder order = inOrder(cache, repo);
        order.verify(cache).currentGeneration("variant");
        order.verify(repo).findByVariantId("v");
    }
}
