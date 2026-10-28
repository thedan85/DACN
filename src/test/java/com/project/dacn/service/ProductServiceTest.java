package com.project.dacn.service;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.request.catalog.ProductFilter;
import com.project.dacn.dto.request.catalog.ProductUpdateRequest;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.dto.response.catalog.ProductAttributeValueResponse;
import com.project.dacn.dto.response.catalog.ProductDetailResponse;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.dto.response.catalog.VariantAttributeValueResponse;
import com.project.dacn.dto.response.catalog.VariantDetailResponse;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.ProductAttributeValue;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.catalog.VariantAttributeValue;
import com.project.dacn.entity.engagement.Review;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.mapper.engagement.EngagementMapper;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import com.project.dacn.repository.catalog.ProductPriceRange;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.engagement.ReviewRepository;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.CatalogCacheKeys;
import com.project.dacn.service.catalog.ProductService;
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
import org.springframework.data.domain.Sort;

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
    ProductAttributeValueRepository productAttributeValueRepo;

    @Mock
    VariantAttributeValueRepository variantAttributeValueRepo;

    @Mock
    ReviewRepository reviewRepo;

    @Mock
    CatalogMapper mapper;

    @Mock
    EngagementMapper engagementMapper;

    @Mock
    CatalogCache cache;

    @InjectMocks
    ProductService service;

    @Test
    void searchCacheHitOverlaysStockAndSkipsRepository() {
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(1));
        when(cache.get(startsWith("pim:product:search:1:"), any()))
                .thenReturn(Optional.of(new PageResponse<>(List.of(response(null, null, null)),
                        0, 20, 1, 1)));
        VariantRepository.ProductStockView stock = mock(VariantRepository.ProductStockView.class);
        when(stock.getProductId()).thenReturn("PRD_1");
        when(stock.getTotalStock()).thenReturn(4L);
        when(variantRepo.findStockByProductIdIn(List.of("PRD_1"))).thenReturn(List.of(stock));

        PageResponse<ProductResponse> result = service.search(
                new ProductFilter(null, ProductStatus.ACTIVE, null), PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).stock()).isEqualTo(4);
        verifyNoInteractions(repo);
    }

    @Test
    void blankCategoryFilterSharesTheUnfilteredCacheKey() {
        Page<Product> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(6));
        when(cache.get(any(), any())).thenReturn(Optional.empty());
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(empty);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

        service.search(new ProductFilter(null, ProductStatus.ACTIVE, "  "), PageRequest.of(0, 20));
        service.search(new ProductFilter(null, ProductStatus.ACTIVE, null), PageRequest.of(0, 20));

        verify(cache, times(2)).get(keys.capture(), any());
        assertThat(keys.getAllValues()).containsOnly(CatalogCacheKeys.productSearch(6,
                new ProductFilter(null, ProductStatus.ACTIVE, null), PageRequest.of(0, 20)));
    }

    @Test
    void getBySlugCacheHitOverlaysLiveStockAndSkipsRepository() {
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(1));
        when(cache.get(eq("pim:product:slug:1:ember-tetra"), any()))
                .thenReturn(Optional.of(response(null, null, null)));
        VariantRepository.ProductStockView stock = mock(VariantRepository.ProductStockView.class);
        when(stock.getTotalStock()).thenReturn(30L);
        when(variantRepo.findStockByProductId("PRD_1")).thenReturn(Optional.of(stock));

        ProductResponse result = service.getBySlug("Ember-Tetra");

        assertThat(result.stock()).isEqualTo(30);
        verify(repo, never()).findBySlugIgnoreCase(any());
    }

    @Test
    void getBySlugCacheHitForProductWithoutVariantsOverlaysZeroStock() {
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(1));
        when(cache.get(eq("pim:product:slug:1:ember-tetra"), any()))
                .thenReturn(Optional.of(response(null, null, null)));
        when(variantRepo.findStockByProductId("PRD_1")).thenReturn(Optional.empty());

        ProductResponse result = service.getBySlug("Ember-Tetra");

        assertThat(result.stock()).isZero();
        verify(repo, never()).findBySlugIgnoreCase(any());
    }

    @Test
    void listOverlayMapsProductsWithoutVariantsToZeroStock() {
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(2));
        when(cache.get(startsWith("pim:product:search:2:"), any()))
                .thenReturn(Optional.of(new PageResponse<>(List.of(
                        response(null, null, null),
                        new ProductResponse("PRD_2", "congo", "C", "d", null, null, null,
                                ProductStatus.ACTIVE, null, null, null, null, null, null, null)),
                        0, 20, 2, 1)));
        VariantRepository.ProductStockView stock = mock(VariantRepository.ProductStockView.class);
        when(stock.getProductId()).thenReturn("PRD_1");
        when(stock.getTotalStock()).thenReturn(7L);
        when(variantRepo.findStockByProductIdIn(List.of("PRD_1", "PRD_2")))
                .thenReturn(List.of(stock));

        PageResponse<ProductResponse> result = service.search(
                new ProductFilter(null, ProductStatus.ACTIVE, null), PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(ProductResponse::stock).containsExactly(7, 0);
    }

    @Test
    void getBySlugMissStoresScrubbedPayloadAndReturnsLiveStock() {
        Product p = product(1L);
        ProductPriceRange range = new ProductPriceRange(1L,
                new BigDecimal("179000"), new BigDecimal("199000"), 30L);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(1));
        when(cache.get(eq("pim:product:slug:1:ember-tetra"), any())).thenReturn(Optional.empty());
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of(range));
        when(mapper.toProductResponse(p, range.minPrice(), range.maxPrice(), 30))
                .thenReturn(response(range.minPrice(), range.maxPrice(), 30));
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);

        ProductResponse result = service.getBySlug("ember-tetra");

        assertThat(result.stock()).isEqualTo(30);
        verify(cache).put(eq("pim:product:slug:1:ember-tetra"), stored.capture(),
                eq(Duration.ofMinutes(5)));
        assertThat(((ProductResponse) stored.getValue()).stock()).isNull();
    }

    @Test
    void publicAdminReadNeverTouchesTheCache() {
        Product p = product(1L);
        p.setStatus(ProductStatus.DRAFT);
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(response(null, null, 0));

        service.getBySlug("ember-tetra", true);

        verifyNoInteractions(cache);
    }

    @Test
    void generationIsReadBeforeTheRepository() {
        Product p = product(1L);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.empty());
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(response(null, null, 0));

        service.getBySlug("ember-tetra");

        InOrder order = inOrder(cache, repo);
        order.verify(cache).currentGeneration("product");
        order.verify(repo).findBySlugIgnoreCase("ember-tetra");
    }

    @Test
    void searchCacheHitOverlaysStockAndSkipsTheQuery() {
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(2));
        when(cache.get(startsWith("pim:product:search:2:"), any()))
                .thenReturn(Optional.of(new PageResponse<>(List.of(response(null, null, null)),
                        0, 20, 1, 1)));
        VariantRepository.ProductStockView stock = mock(VariantRepository.ProductStockView.class);
        when(stock.getProductId()).thenReturn("PRD_1");
        when(stock.getTotalStock()).thenReturn(7L);
        when(variantRepo.findStockByProductIdIn(List.of("PRD_1"))).thenReturn(List.of(stock));

        PageResponse<ProductResponse> result = service.search(
                new ProductFilter(null, ProductStatus.ACTIVE, null), PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).stock()).isEqualTo(7);
        verifyNoInteractions(repo);
    }

    @Test
    void searchMissStoresScrubbedPage() {
        Product p = product(1L);
        Page<Product> page = new PageImpl<>(List.of(p), PageRequest.of(0, 20), 1);
        ProductPriceRange range = new ProductPriceRange(1L, null, null, 30L);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(3));
        when(cache.get(startsWith("pim:product:search:3:"), any())).thenReturn(Optional.empty());
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(page);
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of(range));
        when(mapper.toProductResponse(p, null, null, 30)).thenReturn(response(null, null, 30));
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);

        PageResponse<ProductResponse> result = service.search(
                new ProductFilter(null, ProductStatus.ACTIVE, null), PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).stock()).isEqualTo(30);
        verify(cache).put(startsWith("pim:product:search:3:"), stored.capture(),
                eq(Duration.ofMinutes(2)));
        assertThat(((PageResponse<ProductResponse>) stored.getValue())
                .getContent().get(0).stock()).isNull();
    }

    @Test
    void emptySearchResultIsNotCached() {
        Page<Product> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(3));
        when(cache.get(startsWith("pim:product:search:3:"), any())).thenReturn(Optional.empty());
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(empty);

        service.search(new ProductFilter("nothing", ProductStatus.ACTIVE, null), PageRequest.of(0, 20));

        verify(cache, never()).put(any(), any(), any());
    }

    @Test
    void longKeywordBypassesTheCache() {
        Page<Product> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(empty);

        service.search(new ProductFilter("x".repeat(65), ProductStatus.ACTIVE, null),
                PageRequest.of(0, 20));

        verifyNoInteractions(cache);
    }

    @Test
    void deleteBumpsProductAndVariantGenerationsAfterCommit() {
        when(repo.findByProductId("p")).thenReturn(Optional.of(product(1L)));

        service.delete("p");

        verify(cache).bumpAfterCommit("product", "variant");
    }

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
                new ProductCreateRequest("P", "d", null, "gone", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getMissingThrowsNotFound() {
        when(repo.findBySlugIgnoreCase("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getBySlug("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getDraftProductHidesIt() {
        Product p = product(1L);
        p.setStatus(ProductStatus.DRAFT);
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.getBySlug("ember-tetra"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getDraftProductVisibleToCatalogManager() {
        Product p = product(1L);
        p.setStatus(ProductStatus.DRAFT);
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(response(null, null, 0));

        ProductResponse result = service.getBySlug("ember-tetra", true);

        assertThat(result.name()).isEqualTo("P");
    }

    @Test
    void searchReturnsPageResponseWithoutPricesWhenProductHasNoVariants() {
        Product p = product(1L);
        Page<Product> page = new PageImpl<>(List.of(p), Pageable.unpaged(), 1);
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(page);
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(response(null, null, 0));

        PageResponse<ProductResponse> result = service.search(
                new ProductFilter(null, ProductStatus.ACTIVE, null), Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).minPrice()).isNull();
        assertThat(result.getContent().get(0).maxPrice()).isNull();
        assertThat(result.getContent().get(0).stock()).isZero();
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(repo).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class));
    }

    @Test
    void searchIncludesMinAndMaxPriceFromVariants() {
        Product p = product(1L);
        Page<Product> page = new PageImpl<>(List.of(p), Pageable.unpaged(), 1);
        ProductPriceRange range = new ProductPriceRange(
                1L, new BigDecimal("179000"), new BigDecimal("199000"), 30L);
        when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(page);
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of(range));
        when(mapper.toProductResponse(p, range.minPrice(), range.maxPrice(), 30))
                .thenReturn(response(range.minPrice(), range.maxPrice(), 30));

        PageResponse<ProductResponse> result = service.search(
                new ProductFilter(null, ProductStatus.ACTIVE, null), Pageable.unpaged());

        assertThat(result.getContent().get(0).minPrice()).isEqualByComparingTo("179000");
        assertThat(result.getContent().get(0).maxPrice()).isEqualByComparingTo("199000");
        assertThat(result.getContent().get(0).stock()).isEqualTo(30);
    }

    @Test
    void getIncludesMinAndMaxPriceFromVariants() {
        Product p = product(1L);
        ProductPriceRange range = new ProductPriceRange(
                1L, new BigDecimal("179000"), new BigDecimal("199000"), 30L);
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of(range));
        when(mapper.toProductResponse(p, range.minPrice(), range.maxPrice(), 30))
                .thenReturn(response(range.minPrice(), range.maxPrice(), 30));

        ProductResponse result = service.getBySlug("ember-tetra");

        assertThat(result.minPrice()).isEqualByComparingTo("179000");
        assertThat(result.maxPrice()).isEqualByComparingTo("199000");
        assertThat(result.stock()).isEqualTo(30);
    }

    @Test
    void updateAssignsOwnedMediaAsThumbnail() {
        Product p = product(1L);
        Media media = Media.builder().mediaId("MED_1").product(p).build();
        when(repo.findByProductId("PRD_1")).thenReturn(Optional.of(p));
        when(mediaRepo.findByMediaId("MED_1")).thenReturn(Optional.of(media));
        when(repo.save(p)).thenReturn(p);
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());

        service.update("PRD_1", new ProductUpdateRequest(null, null, null, "MED_1", null, null, null));

        assertThat(p.getThumbnail()).isSameAs(media);
    }

    @Test
    void updateRejectsMediaOwnedByAnotherProduct() {
        Product p = product(1L);
        Media foreign = Media.builder().mediaId("MED_x").product(product(2L)).build();
        when(repo.findByProductId("PRD_1")).thenReturn(Optional.of(p));
        when(mediaRepo.findByMediaId("MED_x")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.update("PRD_1",
                new ProductUpdateRequest(null, null, null, "MED_x", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateRejectsUnknownMediaId() {
        Product p = product(1L);
        when(repo.findByProductId("PRD_1")).thenReturn(Optional.of(p));
        when(mediaRepo.findByMediaId("MED_nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("PRD_1",
                new ProductUpdateRequest(null, null, null, "MED_nope", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void detailGroupsVariantAttributesAndMediaAndLoadsReviews() {
        Product p = product(1L);
        ProductResponse base = response(null, null, 0);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.empty());
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(base);

        ProductAttributeValueResponse productAttribute = new ProductAttributeValueResponse(
                "PAV_1", "PRD_1", "ATTR_1", "Temperature", "AV_1", "25C", null);
        when(productAttributeValueRepo.findByProduct_ProductId("PRD_1"))
                .thenReturn(List.of(ProductAttributeValue.builder().build()));
        when(mapper.toProductAttributeValueResponse(any())).thenReturn(productAttribute);

        Variant v1 = Variant.builder().variantId("VAR_1").sku("SKU-1")
                .price(new BigDecimal("100")).stock(5).build();
        Variant v2 = Variant.builder().variantId("VAR_2").sku("SKU-2")
                .price(new BigDecimal("200")).stock(0).build();
        when(variantRepo.findByProduct_ProductId("PRD_1")).thenReturn(List.of(v1, v2));

        VariantAttributeValueResponse variantAttribute = new VariantAttributeValueResponse(
                "VAV_1", "VAR_1", "ATTR_2", "Color", "AV_2", "Red", null);
        when(variantAttributeValueRepo.findByVariant_Product_ProductId("PRD_1"))
                .thenReturn(List.of(VariantAttributeValue.builder().build()));
        when(mapper.toVariantAttributeValueResponse(any())).thenReturn(variantAttribute);

        MediaResponse media = new MediaResponse("MED_1", "url", null, "VAR_1", false, null, null);
        when(mediaRepo.findByVariant_Product_ProductId("PRD_1"))
                .thenReturn(List.of(Media.builder().build()));
        when(mapper.toMediaResponse(any())).thenReturn(media);

        ReviewResponse review = new ReviewResponse(
                "REV_1", "VAR_1", null, "An", false, 5, "nice", null, null);
        when(reviewRepo.findByVariant_Product_ProductIdOrderByCreatedAtDesc(
                "PRD_1", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(Review.builder().build()),
                        PageRequest.of(0, 20), 1));
        when(engagementMapper.toReviewResponse(any())).thenReturn(review);

        ProductDetailResponse result = service.getDetail("ember-tetra", false, PageRequest.of(0, 20));

        assertThat(result.product()).isSameAs(base);
        assertThat(result.attributes()).containsExactly(productAttribute);
        assertThat(result.variants()).hasSize(2);
        assertThat(result.variants().get(0).variantId()).isEqualTo("VAR_1");
        assertThat(result.variants().get(0).price()).isEqualByComparingTo("100");
        assertThat(result.variants().get(0).stock()).isEqualTo(5);
        assertThat(result.variants().get(0).attributes()).containsExactly(variantAttribute);
        assertThat(result.variants().get(0).media()).containsExactly(media);
        assertThat(result.variants().get(1).attributes()).isEmpty();
        assertThat(result.variants().get(1).media()).isEmpty();
        assertThat(result.reviews().getContent()).containsExactly(review);
        assertThat(result.reviews().getTotalElements()).isEqualTo(1);
        assertThat(result.reviews().getPage()).isZero();
        verify(engagementMapper, never()).toStaffReviewResponse(any());
    }

    @Test
    void detailCacheHitOverlaysStockAndSkipsDetailQueries() {
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(9));
        ProductDetailResponse cached = new ProductDetailResponse(
                response(null, null, null), List.of(),
                List.of(variantDetail("VAR_1", null), variantDetail("VAR_2", null)),
                new PageResponse<>(List.of(), 0, 20, 0, 0));
        when(cache.get(eq("pim:product:detail:9:ember-tetra:0:20:none"), any()))
                .thenReturn(Optional.of(cached));
        VariantRepository.VariantStockView stock = stockView("VAR_1", 5);
        when(variantRepo.findStockByVariantIdIn(List.of("VAR_1", "VAR_2")))
                .thenReturn(List.of(stock));

        ProductDetailResponse result = service.getDetail("ember-tetra", false, PageRequest.of(0, 20));

        assertThat(result.product().stock()).isEqualTo(5);
        assertThat(result.variants()).extracting(VariantDetailResponse::stock)
                .containsExactly(5, 0);
        verifyNoInteractions(repo, mediaRepo, productAttributeValueRepo,
                variantAttributeValueRepo, reviewRepo);
    }

    @Test
    void detailCacheHitForProductWithoutVariantsOverlaysZeroStockWithoutQuery() {
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(9));
        when(cache.get(any(), any())).thenReturn(Optional.of(new ProductDetailResponse(
                response(null, null, null), List.of(), List.of(),
                new PageResponse<>(List.of(), 0, 20, 0, 0))));

        ProductDetailResponse result = service.getDetail("ember-tetra", false, PageRequest.of(0, 20));

        assertThat(result.product().stock()).isZero();
        verify(variantRepo, never()).findStockByVariantIdIn(any());
    }

    @Test
    void detailCacheMissStoresAggregateWithStockScrubbed() {
        Product p = product(1L);
        ProductResponse base = response(null, null, 0);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(9));
        when(cache.get(any(), any())).thenReturn(Optional.empty());
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(base);

        ProductAttributeValueResponse productAttribute = new ProductAttributeValueResponse(
                "PAV_1", "PRD_1", "ATTR_1", "Temperature", "AV_1", "25C", null);
        when(productAttributeValueRepo.findByProduct_ProductId("PRD_1"))
                .thenReturn(List.of(ProductAttributeValue.builder().build()));
        when(mapper.toProductAttributeValueResponse(any())).thenReturn(productAttribute);

        Variant v1 = Variant.builder().variantId("VAR_1").sku("SKU-1")
                .price(new BigDecimal("100")).stock(5).build();
        Variant v2 = Variant.builder().variantId("VAR_2").sku("SKU-2")
                .price(new BigDecimal("200")).stock(0).build();
        when(variantRepo.findByProduct_ProductId("PRD_1")).thenReturn(List.of(v1, v2));
        when(variantAttributeValueRepo.findByVariant_Product_ProductId("PRD_1"))
                .thenReturn(List.of());
        when(mediaRepo.findByVariant_Product_ProductId("PRD_1")).thenReturn(List.of());
        when(reviewRepo.findByVariant_Product_ProductIdOrderByCreatedAtDesc(
                "PRD_1", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<Review>(List.of(), PageRequest.of(0, 20), 0));

        ProductDetailResponse result = service.getDetail("ember-tetra", false, PageRequest.of(0, 20));

        ArgumentCaptor<ProductDetailResponse> stored =
                ArgumentCaptor.forClass(ProductDetailResponse.class);
        verify(cache).put(eq("pim:product:detail:9:ember-tetra:0:20:none"), stored.capture(),
                eq(CatalogCacheKeys.PRODUCT_DETAIL_AGGREGATE_TTL));
        assertThat(stored.getValue().product().stock()).isNull();
        assertThat(stored.getValue().variants())
                .allSatisfy(variant -> assertThat(variant.stock()).isNull());
        assertThat(result.product().stock()).isZero();
        assertThat(result.variants().get(0).stock()).isEqualTo(5);
    }

    @Test
    void detailSkipsAggregateCacheForNonStandardReviewPage() {
        Product p = product(1L);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(9));
        when(cache.get(any(), any())).thenReturn(Optional.empty());
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(response(null, null, 0));
        when(reviewRepo.findByVariant_Product_ProductIdOrderByCreatedAtDesc(
                "PRD_1", PageRequest.of(0, 7)))
                .thenReturn(new PageImpl<Review>(List.of(), PageRequest.of(0, 7), 3));

        ProductDetailResponse result =
                service.getDetail("ember-tetra", false, PageRequest.of(0, 7));

        assertThat(result.reviews().getPage()).isZero();
        assertThat(result.reviews().getSize()).isEqualTo(7);
        assertThat(result.reviews().getTotalElements()).isEqualTo(3);
        verify(cache, never()).get(startsWith("pim:product:detail:"), any());
        verify(cache, never()).put(startsWith("pim:product:detail:"), any(), any());
    }

    @Test
    void detailSkipsAggregateCacheWhenReviewPageIsSorted() {
        Product p = product(1L);
        PageRequest sorted = PageRequest.of(0, 20, Sort.by("rating").ascending());
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.of(9));
        when(cache.get(any(), any())).thenReturn(Optional.empty());
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(response(null, null, 0));
        when(reviewRepo.findByVariant_Product_ProductIdOrderByCreatedAtDesc("PRD_1", sorted))
                .thenReturn(new PageImpl<Review>(List.of(), sorted, 0));

        service.getDetail("ember-tetra", false, sorted);

        verify(cache, never()).get(startsWith("pim:product:detail:"), any());
        verify(cache, never()).put(startsWith("pim:product:detail:"), any(), any());
    }

    @Test
    void detailExposesReviewCustomerIdOnlyToStaff() {
        Product p = product(1L);
        ProductResponse base = response(null, null, 0);
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(base);
        ReviewResponse staffReview = new ReviewResponse(
                "REV_1", "VAR_1", "CUS_1", "An", false, 5, "nice", null, null);
        when(reviewRepo.findByVariant_Product_ProductIdOrderByCreatedAtDesc(
                "PRD_1", PageRequest.of(1, 20)))
                .thenReturn(new PageImpl<>(List.of(Review.builder().build()),
                        PageRequest.of(1, 20), 21));
        when(engagementMapper.toStaffReviewResponse(any())).thenReturn(staffReview);

        ProductDetailResponse result = service.getDetail("ember-tetra", true, PageRequest.of(1, 20));

        assertThat(result.reviews().getContent()).containsExactly(staffReview);
        assertThat(result.reviews().getPage()).isEqualTo(1);
        assertThat(result.reviews().getSize()).isEqualTo(20);
        assertThat(result.reviews().getTotalElements()).isEqualTo(21);
        verify(engagementMapper, never()).toReviewResponse(any());
        verifyNoInteractions(cache);
    }

    @Test
    void detailPutsTheDefaultVariantFirst() {
        Product p = product(1L);
        ProductResponse base = response(null, null, 0);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.empty());
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(base);
        when(productAttributeValueRepo.findByProduct_ProductId("PRD_1")).thenReturn(List.of());
        when(variantRepo.findByProduct_ProductId("PRD_1")).thenReturn(List.of(
                Variant.builder().variantId("ember-tetra-gold").sku("SKU-G")
                        .price(new BigDecimal("100")).stock(5).build(),
                Variant.builder().variantId("ember-tetra-default").sku("SKU-D")
                        .price(new BigDecimal("100")).stock(5).build()));
        when(variantAttributeValueRepo.findByVariant_Product_ProductId("PRD_1"))
                .thenReturn(List.of());
        when(mediaRepo.findByVariant_Product_ProductId("PRD_1")).thenReturn(List.of());
        when(reviewRepo.findByVariant_Product_ProductIdOrderByCreatedAtDesc(
                "PRD_1", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        ProductDetailResponse result = service.getDetail("ember-tetra", false, PageRequest.of(0, 20));

        assertThat(result.variants()).extracting(VariantDetailResponse::variantId)
                .containsExactly("ember-tetra-default", "ember-tetra-gold");
    }

    @Test
    void detailFallsBackToThumbnailForOnlyVariantWithoutMedia() {
        Product p = product(1L);
        ProductResponse base = new ProductResponse("PRD_1", "ember-tetra", "P", "d", null,
                "https://img", "MED_9", ProductStatus.ACTIVE, null, null, null, null, 0,
                null, null);
        when(cache.currentGeneration("product")).thenReturn(OptionalLong.empty());
        when(repo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(p));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of());
        when(mapper.toProductResponse(p, null, null, 0)).thenReturn(base);

        Variant only = Variant.builder().variantId("VAR_1").sku("SKU-1")
                .price(new BigDecimal("100")).stock(5).build();
        when(variantRepo.findByProduct_ProductId("PRD_1")).thenReturn(List.of(only));
        when(mediaRepo.findByVariant_Product_ProductId("PRD_1")).thenReturn(List.of());

        Media thumbnail = Media.builder().mediaId("MED_9").url("https://img").build();
        MediaResponse thumbnailResponse = new MediaResponse(
                "MED_9", "https://img", "PRD_1", null, false, null, null);
        when(mediaRepo.findByMediaId("MED_9")).thenReturn(Optional.of(thumbnail));
        when(mapper.toMediaResponse(thumbnail)).thenReturn(thumbnailResponse);
        when(reviewRepo.findByVariant_Product_ProductIdOrderByCreatedAtDesc(
                "PRD_1", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<Review>(List.of(), PageRequest.of(0, 20), 0));

        ProductDetailResponse result = service.getDetail("ember-tetra", false, PageRequest.of(0, 20));

        assertThat(result.variants()).singleElement()
                .satisfies(variant -> assertThat(variant.media()).containsExactly(thumbnailResponse));
    }

    private static VariantDetailResponse variantDetail(String variantId, Integer stock) {
        return new VariantDetailResponse(variantId, "SKU-" + variantId,
                new BigDecimal("100"), stock, List.of(), List.of());
    }

    private static VariantRepository.VariantStockView stockView(String variantId, int stock) {
        VariantRepository.VariantStockView view = mock(VariantRepository.VariantStockView.class);
        when(view.getVariantId()).thenReturn(variantId);
        when(view.getStock()).thenReturn(stock);
        return view;
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

    private static ProductResponse response(BigDecimal minPrice, BigDecimal maxPrice, Integer stock) {
        return new ProductResponse("PRD_1", "ember-tetra", "P", "d", null, null, null,
                ProductStatus.ACTIVE, null, null, minPrice, maxPrice, stock, null, null);
    }
}
