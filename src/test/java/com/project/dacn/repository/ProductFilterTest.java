package com.project.dacn.repository;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Category_;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Product_;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.ProductService;
import com.project.dacn.dto.request.catalog.ProductFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({ProductService.class, com.project.dacn.mapper.catalog.CatalogMapperImpl.class})
class ProductFilterTest {

    @Autowired
    ProductService service;
    @Autowired
    ProductRepository products;
    @Autowired
    CategoryRepository categories;
    @Autowired
    VariantRepository variants;

    @Test
    void metamodelAttributesResolve() {
        assertThat(Product_.name.getName()).isEqualTo("name");
        assertThat(Product_.status.getName()).isEqualTo("status");
        assertThat(Product_.category.getName()).isEqualTo("category");
        assertThat(Category_.slug.getName()).isEqualTo("slug");
    }

    @Test
    void filterByNameSubstring() {
        products.save(Product.builder().name("Ember Tetra").description("d").build());
        products.save(Product.builder().name("Blue Tang").description("d").build());

        var result = service.search(new ProductFilter("ember", null, null), Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Ember Tetra");
    }

    @Test
    void filterByStatus() {
        products.save(Product.builder().name("Active Fish").description("d").build());
        Product draft = products.save(Product.builder().name("Draft Fish").description("d").build());
        draft.setStatus(ProductStatus.DRAFT);
        products.save(draft);

        var result = service.search(new ProductFilter(null, ProductStatus.DRAFT, null), Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Draft Fish");
    }

    @Test
    void filterByCategory() {
        Category fish = categories.save(Category.builder().name("Fish").slug("pf-fish").build());
        Category plant = categories.save(Category.builder().name("Plant").slug("pf-plant").build());
        products.save(Product.builder().name("Tetra").description("d").category(fish).build());
        products.save(Product.builder().name("Anubias").description("d").category(plant).build());

        var result = service.search(new ProductFilter(null, null, fish.getCategoryId()), Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Tetra");
    }

    @Test
    void filterByParentCategoryIncludesDescendants() {
        Category fish = categories.save(Category.builder().name("Fish").slug("pf-fish-parent").build());
        Category tetra = categories.save(
                Category.builder().name("Tetra").slug("pf-tetra").parent(fish).build());
        Category plant = categories.save(Category.builder().name("Plant").slug("pf-plant-sibling").build());
        products.save(Product.builder().name("Congo Tetra").description("d").category(tetra).build());
        products.save(Product.builder().name("Anubias").description("d").category(plant).build());

        var result = service.search(new ProductFilter(null, null, fish.getCategoryId()), Pageable.unpaged());

        assertThat(result.getContent()).extracting("name").containsExactly("Congo Tetra");
    }

    @Test
    void filterByGrandparentCategoryIncludesGrandchildren() {
        Category plants = categories.save(Category.builder().name("Plants").slug("pf-plants").build());
        Category epiphyte = categories.save(
                Category.builder().name("Epiphyte").slug("pf-epiphyte").parent(plants).build());
        products.save(Product.builder().name("Anubias Nana").description("d").category(epiphyte).build());

        var result = service.search(new ProductFilter(null, null, plants.getCategoryId()), Pageable.unpaged());

        assertThat(result.getContent()).extracting("name").containsExactly("Anubias Nana");
    }

    @Test
    void deletedStatusFilterIncludesDescendants() {
        Category fish = categories.save(Category.builder().name("Fish").slug("pf-fish-deleted").build());
        Category tetra = categories.save(
                Category.builder().name("Tetra").slug("pf-tetra-deleted").parent(fish).build());
        Product gone = products.save(
                Product.builder().name("Gone Tetra").description("d").category(tetra).build());
        gone.setStatus(ProductStatus.DELETED);
        products.save(gone);

        var result = service.search(
                new ProductFilter(null, ProductStatus.DELETED, fish.getCategoryId()), Pageable.unpaged());

        assertThat(result.getContent()).extracting("name").containsExactly("Gone Tetra");
    }

    @Test
    void listIncludesMinAndMaxPriceFromActiveVariants() {
        Product priced = products.save(Product.builder()
                .name("Priced Fish").description("d").build());
        variants.save(Variant.builder().product(priced).sku("PF-1")
                .price(new BigDecimal("179000")).stock(1).build());
        variants.save(Variant.builder().product(priced).sku("PF-2")
                .price(new BigDecimal("199000")).stock(1).build());
        variants.save(Variant.builder().product(priced).sku("PF-DELETED")
                .price(new BigDecimal("99000")).stock(1).deleted(true).build());

        var result = service.search(new ProductFilter(null, null, null), Pageable.unpaged());

        assertThat(result.getContent())
                .filteredOn(response -> response.name().equals("Priced Fish"))
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.minPrice()).isEqualByComparingTo("179000");
                    assertThat(response.maxPrice()).isEqualByComparingTo("199000");
                });
    }

    @Test
    void emptyFilterReturnsAllNonDeleted() {
        products.save(Product.builder().name("One").description("d").build());
        products.save(Product.builder().name("Two").description("d").build());

        var result = service.search(new ProductFilter(null, null, null), Pageable.unpaged());

        assertThat(result.getContent()).hasSize(2);
    }

    @Test
    void explicitDeletedStatusReturnsSoftDeletedForAudit() {
        Product gone = products.save(Product.builder().name("Gone Fish").description("d").build());
        gone.setStatus(ProductStatus.DELETED);
        products.save(gone);
        products.save(Product.builder().name("Live Fish").description("d").build());

        var result = service.search(new ProductFilter(null, ProductStatus.DELETED, null), Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Gone Fish");
    }
}
