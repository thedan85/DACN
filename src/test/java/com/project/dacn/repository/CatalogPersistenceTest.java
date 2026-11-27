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
import com.project.dacn.repository.catalog.ProductDetailBaseRow;
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
    void manualVariantWritesRefreshManagedStockAndUsePortableH2Locks() {
        Product product = productRepository.save(Product.builder().name("Locked").description("d").build());
        Variant variant = variantRepository.saveAndFlush(Variant.builder().product(product)
                .sku("LOCKED").price(BigDecimal.TEN).stock(10).build());
        entityManager.createNativeQuery("update variants set variant_stock=7, stock_revision=1 where id=:id")
                .setParameter("id", variant.getId()).executeUpdate();
        assertThat(variant.getStock()).isEqualTo(10);
        var service = new com.project.dacn.service.catalog.VariantService(variantRepository, productRepository,
                mapper, org.mockito.Mockito.mock(com.project.dacn.service.catalog.CatalogCache.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
        var response = service.update(variant.getVariantId(),
                new com.project.dacn.dto.request.catalog.VariantUpdateRequest("fresh", null, null, null));
        assertThat(response.stock()).isEqualTo(7);
        assertThat(response.stockRevision()).isEqualTo(1L);
        assertThat(variantRepository.findStockByVariantId(variant.getVariantId()).orElseThrow().getStockRevision())
                .isEqualTo(1L);
        assertThat(response.sku()).isEqualTo("fresh");
        assertThatThrownBy(() -> service.replace(variant.getVariantId(),
                new com.project.dacn.dto.request.catalog.VariantUpdateRequest("wrong", BigDecimal.ONE, 7, 0L)))
                .isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
        assertThat(variant.getSku()).isEqualTo("fresh");
        service.delete(variant.getVariantId());
        assertThat(entityManager.createNativeQuery("select variant_stock from variants where id=:id")
                .setParameter("id", variant.getId()).getSingleResult()).isEqualTo(7);
    }

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
    void detailBaseProjectionFlattensAttributesAndActiveVariants() {
        Category category = categoryRepository.save(Category.builder()
                .name("Detail Cat").slug("detail-cat").build());
        Product product = productRepository.save(Product.builder()
                .name("Detail Base").description("d").status(ProductStatus.ACTIVE)
                .category(category).build());

        Attribute attribute = attributeRepository.save(Attribute.builder().name("Temp").build());
        AttributeValue value = attributeValueRepository.save(AttributeValue.builder()
                .attribute(attribute).value("25C").build());
        productAttributeValueRepository.save(ProductAttributeValue.builder()
                .product(product).attribute(attribute).attributeValue(value).build());

        Variant first = variantRepository.save(Variant.builder().product(product).sku("DB-1")
                .price(new BigDecimal("10.00")).stock(3).build());
        Variant second = variantRepository.save(Variant.builder().product(product).sku("DB-2")
                .price(new BigDecimal("20.00")).stock(4).build());
        variantRepository.save(Variant.builder().product(product).sku("DB-DELETED")
                .price(new BigDecimal("99.00")).stock(1).deleted(true).build());

        Media thumbnail = mediaRepository.save(Media.builder()
                .url("https://img/detail.jpg").product(product).build());
        product.setThumbnail(thumbnail);
        productRepository.save(product);
        entityManager.flush();
        entityManager.clear();

        List<ProductDetailBaseRow> rows =
                productRepository.findDetailBaseBySlug("detail-base");

        // 1 product attribute x 2 active variants; the deleted variant is filtered.
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ProductDetailBaseRow::variantId)
                .containsExactlyInAnyOrder(first.getVariantId(), second.getVariantId());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.stockRevision()).isZero();
            assertThat(row.productId()).isEqualTo(product.getProductId());
            assertThat(row.categoryName()).isEqualTo("Detail Cat");
            assertThat(row.thumbnail()).isEqualTo("https://img/detail.jpg");
            assertThat(row.thumbnailMediaId()).isEqualTo(thumbnail.getMediaId());
            assertThat(row.productValueId()).isEqualTo(
                    product.getProductId() + "-" + attribute.getAttributeId());
            assertThat(row.attributeName()).isEqualTo("Temp");
            assertThat(row.attributeValue()).isEqualTo("25C");
        });
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
    void staffMediaListIncludesHiddenOwnersAndSkipsInvisibleOnes() {
        Product active = productRepository.save(Product.builder().name("Staff Active")
                .description("d").build());
        Product draft = productRepository.save(Product.builder().name("Staff Draft")
                .description("d").status(ProductStatus.DRAFT).build());
        Product inactive = productRepository.save(Product.builder().name("Staff Inactive")
                .description("d").status(ProductStatus.INACTIVE).build());
        Product toDelete = productRepository.save(Product.builder().name("Staff Delete")
                .description("d").build());
        Variant deletedVariant = variantRepository.save(Variant.builder().product(active)
                .sku("STAFF-DEL").price(BigDecimal.ONE).stock(1).build());
        Variant draftVariant = variantRepository.save(Variant.builder().product(draft)
                .sku("STAFF-DV").price(BigDecimal.ONE).stock(1).build());
        Media directActive = mediaRepository.save(Media.builder().url("staff-active").product(active).build());
        Media directDraft = mediaRepository.save(Media.builder().url("staff-draft").product(draft).build());
        Media directInactive = mediaRepository.save(Media.builder().url("staff-inactive").product(inactive).build());
        Media variantOfDraft = mediaRepository.save(Media.builder().url("staff-draft-variant").variant(draftVariant).build());
        mediaRepository.save(Media.builder().url("staff-deleted-product").product(toDelete).build());
        mediaRepository.save(Media.builder().url("staff-deleted-variant").variant(deletedVariant).build());
        mediaRepository.save(Media.builder().url("staff-orphan").build());
        deletedVariant.setDeleted(true);
        variantRepository.save(deletedVariant);
        toDelete.setStatus(ProductStatus.DELETED);
        productRepository.save(toDelete);
        entityManager.flush();
        entityManager.clear();

        Statistics stats = statistics();
        stats.clear();
        var measured = mediaRepository.findStaffVisible(PageRequest.of(0, 3, Sort.by("id")));
        assertThat(measured.map(mapper::toMediaResponse).getContent()).hasSize(3);
        assertThat(measured.getTotalElements()).isEqualTo(4);
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);

        org.springframework.data.domain.Page<Media> page = mediaRepository
                .findStaffVisible(org.springframework.data.domain.Pageable.unpaged());
        // Mapping must not throw: owners of kept rows are entity-loadable.
        java.util.List<String> ids = page.map(mapper::toMediaResponse).getContent().stream()
                .map(com.project.dacn.dto.response.catalog.MediaResponse::mediaId)
                .toList();

        assertThat(ids).containsExactlyInAnyOrder(
                directActive.getMediaId(),
                directDraft.getMediaId(),
                directInactive.getMediaId(),
                variantOfDraft.getMediaId());
        assertThat(page.getTotalElements()).isEqualTo(4);

        org.springframework.data.domain.Page<Media> first = mediaRepository.findStaffVisible(
                org.springframework.data.domain.PageRequest.of(0, 3));
        org.springframework.data.domain.Page<Media> second = mediaRepository.findStaffVisible(
                org.springframework.data.domain.PageRequest.of(1, 3));
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(3);
        assertThat(second.getContent()).hasSize(1);
    }

    @Test
    void staffMediaRetainsVisibleOwnerWhenOtherLegacyOwnerIsDeleted() {
        Product active = productRepository.save(Product.builder().name("Legacy Active").description("d").build());
        Product deleted = productRepository.save(Product.builder().name("Legacy Deleted").description("d").build());
        Variant liveVariant = variantRepository.save(Variant.builder().product(active)
                .sku("LEGACY-LIVE").price(BigDecimal.ONE).stock(1).build());
        Variant deletedVariant = variantRepository.save(Variant.builder().product(active)
                .sku("LEGACY-DELETED").price(BigDecimal.ONE).stock(1).build());
        Media productOwned = mediaRepository.save(Media.builder().url("legacy-product")
                .product(active).variant(deletedVariant).build());
        Media variantOwned = mediaRepository.save(Media.builder().url("legacy-variant")
                .product(deleted).variant(liveVariant).build());
        deleted.setStatus(ProductStatus.DELETED);
        deletedVariant.setDeleted(true);
        entityManager.flush();
        entityManager.clear();

        var rows = mediaRepository.findStaffVisible(PageRequest.of(0, 20, Sort.by("id")))
                .map(mapper::toMediaResponse).getContent();
        assertThat(rows).hasSize(2);
        assertThat(rows).filteredOn(row -> row.mediaId().equals(productOwned.getMediaId()))
                .singleElement().satisfies(row -> {
                    assertThat(row.productId()).isEqualTo(active.getProductId());
                    assertThat(row.variantId()).isNull();
                });
        assertThat(rows).filteredOn(row -> row.mediaId().equals(variantOwned.getMediaId()))
                .singleElement().satisfies(row -> {
                    assertThat(row.productId()).isNull();
                    assertThat(row.variantId()).isEqualTo(liveVariant.getVariantId());
                });
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
    void liveStockProjectionsUseTheActiveProductVisibilityPredicate() {
        Product active = productRepository.save(Product.builder()
                .name("Snapshot Active").description("d").status(ProductStatus.ACTIVE).build());
        Variant activeLive = variantRepository.save(Variant.builder().product(active).sku("SNAP-A")
                .price(BigDecimal.ONE).stock(4).build());
        Variant activeDeleted = variantRepository.save(Variant.builder().product(active)
                .sku("SNAP-A-DEL").price(BigDecimal.ONE).stock(9).deleted(true).build());
        Product draft = productRepository.save(Product.builder()
                .name("Snapshot Draft").description("d").status(ProductStatus.DRAFT).build());
        Variant draftLive = variantRepository.save(Variant.builder().product(draft).sku("SNAP-D")
                .price(BigDecimal.ONE).stock(6).build());
        entityManager.flush();
        entityManager.clear();

        assertThat(variantRepository.findStockByProductId(active.getProductId()).orElseThrow()
                .getTotalStock()).isEqualTo(4);
        assertThat(variantRepository.findStockByProductId(draft.getProductId())).isEmpty();
        assertThat(variantRepository.findStockByProductIdIn(
                List.of(active.getProductId(), draft.getProductId())))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.getProductId()).isEqualTo(active.getProductId());
                    assertThat(view.getTotalStock()).isEqualTo(4L);
                });

        assertThat(variantRepository.findStockByVariantId(activeLive.getVariantId()).orElseThrow()
                .getStock()).isEqualTo(4);
        assertThat(variantRepository.findStockByVariantId(activeLive.getVariantId()).orElseThrow()
                .getStockRevision()).isZero();
        assertThat(variantRepository.findStockByVariantId(activeDeleted.getVariantId())).isEmpty();
        assertThat(variantRepository.findStockByVariantId(draftLive.getVariantId())).isEmpty();
        assertThat(variantRepository.findStockByVariantIdIn(List.of(
                activeLive.getVariantId(), activeDeleted.getVariantId(), draftLive.getVariantId())))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.getVariantId()).isEqualTo(activeLive.getVariantId());
                    assertThat(view.getStock()).isEqualTo(4);
                    assertThat(view.getStockRevision()).isZero();
                });
    }

    @Test
    void liveStockProjectionsBatchAWholePageInOneStatement() {
        Product first = productRepository.save(Product.builder()
                .name("Batch One").description("d").status(ProductStatus.ACTIVE).build());
        Product second = productRepository.save(Product.builder()
                .name("Batch Two").description("d").status(ProductStatus.ACTIVE).build());
        List<String> variantIds = List.of(
                variantRepository.save(Variant.builder().product(first).sku("BAT-1")
                        .price(BigDecimal.ONE).stock(1).build()).getVariantId(),
                variantRepository.save(Variant.builder().product(first).sku("BAT-2")
                        .price(BigDecimal.ONE).stock(2).build()).getVariantId(),
                variantRepository.save(Variant.builder().product(first).sku("BAT-3")
                        .price(BigDecimal.ONE).stock(3).build()).getVariantId(),
                variantRepository.save(Variant.builder().product(second).sku("BAT-4")
                        .price(BigDecimal.ONE).stock(4).build()).getVariantId(),
                variantRepository.save(Variant.builder().product(second).sku("BAT-5")
                        .price(BigDecimal.ONE).stock(5).build()).getVariantId());
        entityManager.flush();
        entityManager.clear();
        Statistics stats = statistics();

        stats.clear();
        assertThat(variantRepository.findStockByVariantIdIn(variantIds)).hasSize(5);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);

        stats.clear();
        assertThat(variantRepository.findStockByProductIdIn(
                List.of(first.getProductId(), second.getProductId()))).hasSize(2);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void snapshotProductAggregateAgreesWithCatalogProjectionAndIncludesZeroVariantProducts() {
        Product active = productRepository.save(Product.builder()
                .name("Snapshot Aggregate Active").description("d").status(ProductStatus.ACTIVE).build());
        Product noVariants = productRepository.save(Product.builder()
                .name("Snapshot Aggregate Empty").description("d").status(ProductStatus.ACTIVE).build());
        Product draft = productRepository.save(Product.builder()
                .name("Snapshot Aggregate Draft").description("d").status(ProductStatus.DRAFT).build());
        Variant live = variantRepository.save(Variant.builder().product(active).sku("SNAP-AGG-1")
                .price(BigDecimal.ONE).stock(4).build());
        variantRepository.save(Variant.builder().product(active).sku("SNAP-AGG-DEL")
                .price(BigDecimal.ONE).stock(9).deleted(true).build());
        Variant draftLive = variantRepository.save(Variant.builder().product(draft).sku("SNAP-AGG-D")
                .price(BigDecimal.ONE).stock(6).build());
        entityManager.flush();
        entityManager.clear();

        List<String> requested = List.of(active.getProductId(), noVariants.getProductId(),
                draft.getProductId());

        assertThat(variantRepository.findStockByProductIdIn(requested))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getProductId()).isEqualTo(active.getProductId());
                    assertThat(row.getTotalStock()).isEqualTo(4L);
                });
        assertThat(variantRepository.findSnapshotStockByProductIdIn(requested))
                .hasSize(2)
                .anySatisfy(row -> {
                    assertThat(row.getProductId()).isEqualTo(active.getProductId());
                    assertThat(row.getTotalStock()).isEqualTo(4L);
                })
                .anySatisfy(row -> {
                    assertThat(row.getProductId()).isEqualTo(noVariants.getProductId());
                    assertThat(row.getTotalStock()).isZero();
                });

        assertThat(variantRepository.findStockByVariantIdIn(
                List.of(live.getVariantId(), draftLive.getVariantId())))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getVariantId()).isEqualTo(live.getVariantId());
                    assertThat(row.getProductId()).isEqualTo(active.getProductId());
                    assertThat(row.getStock()).isEqualTo(4);
                });
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
