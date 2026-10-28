package com.project.dacn.repository;

import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.ProductAttributeValue;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.catalog.VariantAttributeValue;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.dto.response.catalog.CategoryResponse;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.mapper.catalog.CatalogMapperImpl;
import com.project.dacn.repository.catalog.AttributeRepository;
import com.project.dacn.repository.catalog.AttributeValueRepository;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(CatalogMapperImpl.class)
class CatalogPersistenceTest {

    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    AttributeRepository attributeRepository;
    @Autowired
    AttributeValueRepository attributeValueRepository;
    @Autowired
    VariantRepository variantRepository;
    @Autowired
    MediaRepository mediaRepository;
    @Autowired
    VariantAttributeValueRepository variantAttributeValueRepository;
    @Autowired
    ProductAttributeValueRepository productAttributeValueRepository;
    @Autowired
    CatalogMapper mapper;
    @Autowired
    EntityManager entityManager;

    @Test
    void persistCatalogChainThroughBusinessKeys() {
        Category parent = categoryRepository.save(Category.builder().name("Fish").slug("fish").build());
        Category child = categoryRepository.save(Category.builder()
                .name("Betta").slug("betta").parent(parent).build());
        assertThat(child.getCategoryId()).isNotNull();

        Product product = productRepository.save(Product.builder()
                .name("Betta Halfmoon").description("desc").category(child).build());

        Attribute color = attributeRepository.save(Attribute.builder().name("Color").build());
        AttributeValue red = attributeValueRepository.save(AttributeValue.builder()
                .attribute(color).value("Red").build());
        assertThat(red.getAttributeValueId()).isNotNull();

        Variant variant = variantRepository.save(Variant.builder()
                .product(product).sku("BETTA-RED-01")
                .price(new BigDecimal("12.50")).stock(10).build());

        VariantAttributeValue vav = variantAttributeValueRepository.save(VariantAttributeValue.builder()
                .variant(variant).attribute(color).attributeValue(red).build());
        assertThat(vav.getVariantValueId())
                .isEqualTo(variant.getVariantId() + "-" + color.getAttributeId());

        ProductAttributeValue pav = productAttributeValueRepository.save(ProductAttributeValue.builder()
                .product(product).attribute(color).attributeValue(red).build());
        assertThat(pav.getProductValueId())
                .isEqualTo(product.getProductId() + "-" + color.getAttributeId());

        Media media = mediaRepository.save(Media.builder()
                .url("http://img/1.jpg").variant(variant).build());
        assertThat(media.getMediaId()).isNotNull();

        assertThat(variantAttributeValueRepository.findByVariant_VariantId(variant.getVariantId())).hasSize(1);
    }

    @Test
    void duplicateSkuAndSlugRejected() {
        Category c = categoryRepository.save(Category.builder().name("N1").slug("slug-1").build());
        assertThatThrownBy(() -> categoryRepository.saveAndFlush(
                Category.builder().name("N2").slug("slug-1").build()))
                .isInstanceOf(DataIntegrityViolationException.class);

        Product p = productRepository.save(Product.builder()
                .name("P").description("d").category(c).build());
        variantRepository.save(Variant.builder().product(p).sku("SKU-1")
                .price(BigDecimal.ONE).stock(1).build());
        assertThatThrownBy(() -> variantRepository.saveAndFlush(Variant.builder()
                .product(p).sku("SKU-1").price(BigDecimal.ONE).stock(1).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void priceRangeGroupsActiveVariantsOnly() {
        Product product = productRepository.save(Product.builder()
                .name("Price Range").description("desc").build());

        variantRepository.save(Variant.builder().product(product).sku("PRICE-1")
                .price(new BigDecimal("179000")).stock(1).build());
        variantRepository.save(Variant.builder().product(product).sku("PRICE-2")
                .price(new BigDecimal("199000")).stock(1).build());
        variantRepository.save(Variant.builder().product(product).sku("PRICE-DELETED")
                .price(new BigDecimal("99000")).stock(1).deleted(true).build());

        List<com.project.dacn.repository.catalog.ProductPriceRange> ranges =
                variantRepository.findPriceRangesByProductIds(List.of(product.getId()));

        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0).minPrice()).isEqualByComparingTo("179000");
        assertThat(ranges.get(0).maxPrice()).isEqualByComparingTo("199000");
    }

    @Test
    void softDeletedProductHiddenByDefault() {
        Product p = productRepository.save(Product.builder().name("PX").description("d").build());
        p.setStatus(com.project.dacn.common.constant.ProductStatus.DELETED);
        productRepository.save(p);
        assertThat(productRepository.findByProductId(p.getProductId())).isEmpty();
    }

    @Test
    void visibleMediaQueryFiltersHiddenProductsAndDeletedRows() {
        Product active = productRepository.save(Product.builder()
                .name("Media Active").description("d").build());
        Product draft = productRepository.save(Product.builder()
                .name("Media Draft").description("d").status(ProductStatus.DRAFT).build());
        Variant activeVariant = variantRepository.save(Variant.builder().product(active)
                .sku("VIS-A").price(BigDecimal.ONE).stock(1).build());
        Variant draftVariant = variantRepository.save(Variant.builder().product(draft)
                .sku("VIS-D").price(BigDecimal.ONE).stock(1).build());

        Media directActive = mediaRepository.save(
                Media.builder().url("a").product(active).build());
        Media directDraft = mediaRepository.save(
                Media.builder().url("d").product(draft).build());
        Media variantActive = mediaRepository.save(
                Media.builder().url("va").variant(activeVariant).build());
        Media variantDraft = mediaRepository.save(
                Media.builder().url("vd").variant(draftVariant).build());
        Media deleted = mediaRepository.save(
                Media.builder().url("x").product(active).deleted(true).build());

        assertThat(mediaRepository.findVisible(ProductStatus.ACTIVE, Pageable.unpaged()).getContent())
                .extracting(Media::getMediaId)
                .containsExactlyInAnyOrder(directActive.getMediaId(), variantActive.getMediaId())
                .doesNotContain(directDraft.getMediaId(), variantDraft.getMediaId(),
                        deleted.getMediaId());
    }

    @Test
    void stockProjectionsMatchPriceRangeFilterIncludingDeletedVariants() {
        Product product = productRepository.save(Product.builder()
                .name("Stock Parity").description("d").build());
        Variant live = variantRepository.save(Variant.builder().product(product).sku("PAR-LIVE")
                .price(new BigDecimal("179000")).stock(4).build());
        Variant deleted = variantRepository.save(Variant.builder().product(product).sku("PAR-DEL")
                .price(new BigDecimal("99000")).stock(9).deleted(true).build());

        long missPathStock = variantRepository
                .findPriceRangesByProductIds(List.of(product.getId())).get(0).totalStock();
        assertThat(missPathStock).isEqualTo(4);

        VariantRepository.ProductStockView byProduct =
                variantRepository.findStockByProductId(product.getProductId()).orElseThrow();
        assertThat(byProduct.getTotalStock()).isEqualTo(4);

        assertThat(variantRepository.findStockByProductIdIn(List.of(product.getProductId())))
                .singleElement()
                .extracting(VariantRepository.ProductStockView::getTotalStock)
                .isEqualTo(4L);

        assertThat(variantRepository.findStockByVariantId(live.getVariantId()).orElseThrow().getStock())
                .isEqualTo(4);
        assertThat(variantRepository.findStockByVariantId(deleted.getVariantId())).isEmpty();
        assertThat(variantRepository.findStockByVariantIdIn(
                List.of(live.getVariantId(), deleted.getVariantId())))
                .singleElement()
                .extracting(VariantRepository.VariantStockView::getStock)
                .isEqualTo(4);

        // No-variant product: both paths have no row, and both callers map that to stock 0.
        Product noVariants = productRepository.save(Product.builder()
                .name("Stock Parity Empty").description("d").build());
        assertThat(variantRepository.findPriceRangesByProductIds(List.of(noVariants.getId()))).isEmpty();
        assertThat(variantRepository.findStockByProductId(noVariants.getProductId())).isEmpty();
        assertThat(variantRepository.findStockByProductIdIn(List.of(noVariants.getProductId()))).isEmpty();
    }

    @Test
    void categoryPageLoadsParentsInBatches() {
        for (int i = 0; i < 30; i++) {
            Category parent = categoryRepository.save(Category.builder()
                    .name("Z Parent " + i).slug("parent-" + i).build());
            for (int j = 0; j < 2; j++) {
                categoryRepository.save(Category.builder()
                        .name("A Child " + i + "-" + j).slug("child-" + i + "-" + j)
                        .parent(parent).build());
            }
        }
        entityManager.flush();
        entityManager.clear();
        Statistics stats = statistics();
        stats.clear();

        // Sort children first so the page under test contains only rows whose
        // parent is a lazy proxy, matching the real category page.
        List<CategoryResponse> content = categoryRepository
                .findByDeletedFalse(PageRequest.of(0, 60, Sort.by("name")))
                .map(mapper::toCategoryResponse)
                .getContent();

        assertThat(content).hasSize(60)
                .allSatisfy(category -> assertThat(category.parentCategoryId()).isNotNull());
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    @Test
    void productPageLoadsCategoryAndThumbnailInBatches() {
        Category[] categories = new Category[3];
        for (int i = 0; i < categories.length; i++) {
            categories[i] = categoryRepository.save(Category.builder()
                    .name("Category " + i).slug("category-" + i).build());
        }
        for (int i = 0; i < 12; i++) {
            Media thumbnail = mediaRepository.save(
                    Media.builder().url("thumb-" + i + ".jpg").build());
            productRepository.save(Product.builder()
                    .name("Product " + i).slug("product-" + i).description("d")
                    .category(categories[i % categories.length]).thumbnail(thumbnail).build());
        }
        entityManager.flush();
        entityManager.clear();
        Statistics stats = statistics();
        stats.clear();

        List<ProductResponse> content =
                productRepository.findAll(PageRequest.of(0, 12))
                        .map(product -> mapper.toProductResponse(product, null, null, 0))
                        .getContent();

        assertThat(content).hasSize(12)
                .allSatisfy(product -> {
                    assertThat(product.categoryId()).isNotNull();
                    assertThat(product.thumbnail()).isNotNull();
                });
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(5);
    }

    private Statistics statistics() {
        return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }
}
