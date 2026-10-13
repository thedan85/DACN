package com.project.dacn;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Category_;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Product_;
import com.project.dacn.repository.catalog.CategoryRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.service.catalog.ProductService;
import com.project.dacn.spec.ProductFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

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
    void emptyFilterReturnsAllNonDeleted() {
        products.save(Product.builder().name("One").description("d").build());
        products.save(Product.builder().name("Two").description("d").build());

        var result = service.search(new ProductFilter(null, null, null), Pageable.unpaged());

        assertThat(result.getContent()).hasSize(2);
    }
}
