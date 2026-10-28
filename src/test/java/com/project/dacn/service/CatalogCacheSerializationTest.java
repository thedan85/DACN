package com.project.dacn.service;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.ProductFilter;
import com.project.dacn.dto.response.catalog.CategoryResponse;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.dto.response.catalog.ProductAttributeValueResponse;
import com.project.dacn.dto.response.catalog.ProductDetailResponse;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.dto.response.catalog.VariantAttributeValueResponse;
import com.project.dacn.dto.response.catalog.VariantDetailResponse;
import com.project.dacn.dto.response.catalog.VariantResponse;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.mapper.catalog.CatalogMapperImpl;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import com.project.dacn.repository.catalog.ProductPriceRange;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.engagement.ReviewService;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the payloads the catalog cache actually stores through the
 * application's configured Jackson mapper, not a test-local one.
 */
@SpringBootTest
@ActiveProfiles("test")
class CatalogCacheSerializationTest {

    @Autowired
    ObjectMapper mapper;

    @Test
    void productResponseRoundTrips() {
        ProductResponse response = productResponse();

        assertThat(mapper.readValue(mapper.writeValueAsString(response),
                new TypeReference<ProductResponse>() { })).isEqualTo(response);
    }

    @Test
    void storefrontReviewJsonHidesCustomerIdButKeepsName() {
        ReviewResponse storefront = new ReviewResponse(
                "REV_1", "VAR_1", null, "An", false, 5, "nice", null, null);
        ReviewResponse staff = new ReviewResponse(
                "REV_1", "VAR_1", "CUS_1", "An", false, 5, "nice", null, null);


        assertThat(mapper.writeValueAsString(storefront))
                .doesNotContain("customerId")
                .contains("customerName");
        JsonNode storefrontJson = mapper.readTree(mapper.writeValueAsString(storefront));
        assertThat(storefrontJson.has("orderItemId")).isFalse();
        assertThat(storefrontJson.has("verifiedPurchase")).isTrue();
        assertThat(storefrontJson.get("verifiedPurchase").asBoolean()).isFalse();
        assertThat(mapper.writeValueAsString(staff)).contains("CUS_1");
    }

    @Test
    void variantResponseRoundTrips() {
        VariantResponse response = new VariantResponse("VAR_9", "FISH-9",
                new BigDecimal("179000.00"), null, "PRD_1", "Ember Tetra", false,
                Instant.parse("2026-10-25T10:15:30.123456Z"),
                Instant.parse("2026-10-25T10:16:31.654321Z"));

        assertThat(mapper.readValue(mapper.writeValueAsString(response),
                new TypeReference<VariantResponse>() { })).isEqualTo(response);
    }

    @Test
    void categoryResponseRoundTrips() {
        CategoryResponse response = new CategoryResponse("CAT_1", "Tetra", "tetra", null, false,
                Instant.parse("2026-10-25T10:15:30.123456Z"),
                Instant.parse("2026-10-25T10:16:31.654321Z"));

        assertThat(mapper.readValue(mapper.writeValueAsString(response),
                new TypeReference<CategoryResponse>() { })).isEqualTo(response);
    }

    @Test
    void pageResponseOfProductResponseRoundTrips() {
        PageResponse<ProductResponse> page = new PageResponse<>(List.of(productResponse()),
                0, 20, 1, 1);

        assertThat(mapper.readValue(mapper.writeValueAsString(page),
                new TypeReference<PageResponse<ProductResponse>>() { })).isEqualTo(page);
    }

    @Test
    void secondIdenticalSearchRequestIsServedFromTheCache() {
        Product product = Product.builder().id(1L).productId("PRD_1").slug("ember-tetra")
                .name("Ember Tetra").description("d").status(ProductStatus.ACTIVE).build();
        ProductRepository productRepo = mock(ProductRepository.class);
        VariantRepository variantRepo = mock(VariantRepository.class);
        when(productRepo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product), PageRequest.of(0, 20), 1));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L)))
                .thenReturn(List.of(new ProductPriceRange(1L, new BigDecimal("179000.00"),
                        new BigDecimal("179000.00"), 4L)));
        VariantRepository.ProductStockView stock = mock(VariantRepository.ProductStockView.class);
        when(stock.getProductId()).thenReturn("PRD_1");
        when(stock.getTotalStock()).thenReturn(4L);
        when(variantRepo.findStockByProductIdIn(List.of("PRD_1"))).thenReturn(List.of(stock));

        // A real CatalogCache over an in-memory StringRedisTemplate stand-in.
        ProductService service = new ProductService(productRepo, mock(CategoryRepository.class),
                variantRepo, mock(MediaRepository.class),
                mock(ProductAttributeValueRepository.class),
                mock(VariantAttributeValueRepository.class), mock(ReviewService.class),
                new CatalogMapperImpl(),
                new CatalogCache(inMemoryRedis(), mapper));

        PageResponse<ProductResponse> first = service.search(
                new ProductFilter(null, ProductStatus.ACTIVE, null), PageRequest.of(0, 20));
        PageResponse<ProductResponse> second = service.search(
                new ProductFilter(null, ProductStatus.ACTIVE, null), PageRequest.of(0, 20));

        verify(productRepo, times(1)).findAll(any(Specification.class), any(Pageable.class));
        assertThat(second).isEqualTo(first);
        assertThat(second.getContent().get(0).stock()).isEqualTo(4);
    }

    @Test
    void productDetailRoundTrips() {
        ProductDetailResponse detail = new ProductDetailResponse(
                productResponse(),
                List.of(new ProductAttributeValueResponse(
                        "PAV_1", "PRD_1", "ATTR_1", "Temperature", "AV_1", "25C", null)),
                List.of(new VariantDetailResponse("VAR_1", "SKU-1",
                        new BigDecimal("179000.00"), null,
                        List.of(new VariantAttributeValueResponse(
                                "VAV_1", "VAR_1", "ATTR_2", "Color", "AV_2", "Red", null)),
                        List.of(new MediaResponse("MED_1", "https://x/v.jpg", null, "VAR_1",
                                false, null, null)))),
                new PageResponse<>(List.of(new ReviewResponse(
                        "REV_1", "VAR_1", null, "An", false, 5, "nice", null, null)),
                        0, 20, 1, 1));

        assertThat(mapper.readValue(mapper.writeValueAsString(detail),
                new TypeReference<ProductDetailResponse>() { })).isEqualTo(detail);
    }

    @Test
    void secondIdenticalDetailRequestIsServedFromTheAggregateCache() {
        Product product = Product.builder().id(1L).productId("PRD_1").slug("ember-tetra")
                .name("Ember Tetra").description("d").status(ProductStatus.ACTIVE).build();
        ProductRepository productRepo = mock(ProductRepository.class);
        VariantRepository variantRepo = mock(VariantRepository.class);
        ProductAttributeValueRepository productAttributeRepo =
                mock(ProductAttributeValueRepository.class);
        VariantAttributeValueRepository variantAttributeRepo =
                mock(VariantAttributeValueRepository.class);
        MediaRepository mediaRepo = mock(MediaRepository.class);
        ReviewService reviewService = mock(ReviewService.class);

        when(productRepo.findBySlugIgnoreCase("ember-tetra")).thenReturn(Optional.of(product));
        when(variantRepo.findPriceRangesByProductIds(List.of(1L))).thenReturn(List.of(
                new ProductPriceRange(1L, new BigDecimal("179000.00"),
                        new BigDecimal("179000.00"), 4L)));
        when(productAttributeRepo.findByProduct_ProductId("PRD_1")).thenReturn(List.of());
        when(variantRepo.findByProduct_ProductId("PRD_1")).thenReturn(List.of(
                Variant.builder().variantId("ember-tetra-default").sku("SKU-1")
                        .price(new BigDecimal("179000.00")).stock(4).build()));
        when(variantAttributeRepo.findByVariant_Product_ProductId("PRD_1")).thenReturn(List.of());
        when(mediaRepo.findByVariant_Product_ProductId("PRD_1")).thenReturn(List.of());
        when(reviewService.listForProduct("PRD_1", false, PageRequest.of(0, 20)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        VariantRepository.VariantStockView stock = mock(VariantRepository.VariantStockView.class);
        when(stock.getVariantId()).thenReturn("ember-tetra-default");
        when(stock.getStock()).thenReturn(3);
        when(variantRepo.findStockByVariantIdIn(List.of("ember-tetra-default")))
                .thenReturn(List.of(stock));

        ProductService service = new ProductService(productRepo, mock(CategoryRepository.class),
                variantRepo, mediaRepo, productAttributeRepo, variantAttributeRepo, reviewService,
                new CatalogMapperImpl(), new CatalogCache(inMemoryRedis(), mapper));

        ProductDetailResponse first = service.getDetail("ember-tetra", false, PageRequest.of(0, 20));
        ProductDetailResponse second = service.getDetail("ember-tetra", false, PageRequest.of(0, 20));

        verify(productRepo, times(1)).findBySlugIgnoreCase("ember-tetra");
        verify(variantRepo, times(1)).findByProduct_ProductId("PRD_1");
        verify(reviewService, times(1)).listForProduct("PRD_1", false, PageRequest.of(0, 20));
        verify(variantRepo, times(1)).findStockByVariantIdIn(List.of("ember-tetra-default"));
        assertThat(first.variants().get(0).stock()).isEqualTo(4);
        assertThat(first.product().stock()).isEqualTo(4);
        assertThat(second.variants().get(0).stock()).isEqualTo(3);
        assertThat(second.product().stock()).isEqualTo(3);
    }

    private static ProductResponse productResponse() {
        return new ProductResponse("PRD_1", "ember-tetra", "Ember Tetra", "short", "long",
                "https://x/p.jpg", "MED_1", ProductStatus.ACTIVE, "CAT_1", "Tetra",
                new BigDecimal("179000.00"), new BigDecimal("199000.50"), null,
                Instant.parse("2026-10-25T10:15:30.123456Z"),
                Instant.parse("2026-10-25T10:16:31.654321Z"));
    }

    private static StringRedisTemplate inMemoryRedis() {
        Map<String, String> store = new HashMap<>();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(values.get(anyString())).thenAnswer(call -> store.get(call.<String>getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString())).thenAnswer(call -> store.putIfAbsent(
                call.getArgument(0), call.getArgument(1)) == null);
        when(values.increment(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            long next = Long.parseLong(store.get(key)) + 1;
            store.put(key, Long.toString(next));
            return next;
        });
        doAnswer(call -> {
            store.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        return redis;
    }
}
