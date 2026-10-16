package com.project.dacn.service;

import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.service.catalog.ProductFolderResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductFolderResolverTest {

    private final ProductFolderResolver resolver = new ProductFolderResolver();

    private static Category cat(String id, String slug, Category parent) {
        return Category.builder().categoryId(id).name(slug).slug(slug).parent(parent).build();
    }

    @Test
    void tetraChainBuildsHomeFishTetraProduct() {
        Category livestock = cat("livestock", "livestock", null);
        Category fish = cat("fish", "fish", livestock);
        Category tetra = cat("tetra", "tetra", fish);
        Product p = Product.builder().productId("congo-tetra").name("Congo Tetra").category(tetra).build();

        assertThat(resolver.resolve(p)).isEqualTo("home/fish/tetra/congo-tetra");
    }

    @Test
    void badisChainBuildsHomeFishBadisProduct() {
        Category livestock = cat("livestock", "livestock", null);
        Category fish = cat("fish", "fish", livestock);
        Category badis = cat("badis", "badis", fish);
        Product p = Product.builder().productId("dario-dario").name("Dario Dario").category(badis).build();

        assertThat(resolver.resolve(p)).isEqualTo("home/fish/badis/dario-dario");
    }

    @Test
    void deepPlantChainKeepsAllButRoot() {
        Category livestock = cat("livestock", "livestock", null);
        Category plants = cat("aquatic-plants", "aquatic-plants", livestock);
        Category epiphyte = cat("epiphyte", "epiphyte", plants);
        Product p = Product.builder().productId("anubias-nana").name("Anubias Nana").category(epiphyte).build();

        assertThat(resolver.resolve(p)).isEqualTo("home/aquatic-plants/epiphyte/anubias-nana");
    }

    @Test
    void rootDirectProductKeepsRoot() {
        Category livestock = cat("livestock", "livestock", null);
        Product p = Product.builder().productId("lonely-prod").name("Lonely Prod").category(livestock).build();

        assertThat(resolver.resolve(p)).isEqualTo("home/livestock/lonely-prod");
    }

    @Test
    void nullCategoryFallsBackToProductOnly() {
        Product p = Product.builder().productId("congo-tetra").name("Congo Tetra").category(null).build();

        assertThat(resolver.resolve(p)).isEqualTo("home/congo-tetra");
    }

    @Test
    void slugifyNormalizesNames() {
        assertThat(ProductFolderResolver.slugify("Congo Tetra!")).isEqualTo("congo-tetra");
        assertThat(ProductFolderResolver.slugify("  Red  Cherry__Tetra ")).isEqualTo("red-cherry-tetra");
    }
}
