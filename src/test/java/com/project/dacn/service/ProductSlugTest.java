package com.project.dacn.service;

import com.project.dacn.common.exception.DuplicateResourceException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.request.catalog.ProductUpdateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.mapper.catalog.CatalogMapperImpl;
import com.project.dacn.mapper.engagement.EngagementMapperImpl;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.ProductService;
import com.project.dacn.service.engagement.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({ProductService.class, CatalogMapperImpl.class, EngagementMapperImpl.class})
class ProductSlugTest {

    @MockitoBean
    CatalogCache cache;

    @MockitoBean
    ReviewService reviewService;

    @Autowired
    private ProductService service;

    @Autowired
    private ProductRepository repo;

    private static ProductCreateRequest create(String name, String slug) {
        return new ProductCreateRequest(name, "desc", null, null, slug, null);
    }

    private static ProductUpdateRequest update(String name, String slug) {
        return new ProductUpdateRequest(name, null, null, null, null, slug, null);
    }

    @Test
    void createGeneratesSlugFromVietnameseName() {
        ProductResponse created = service.create(create("Cá Cảnh", null));

        assertThat(created.slug()).isEqualTo("ca-canh");
    }

    @Test
    void duplicateNamesReceiveSuffixedSlugs() {
        ProductResponse first = service.create(create("Ember Tetra", null));
        ProductResponse second = service.create(create("ember tetra", null));

        assertThat(first.slug()).isEqualTo("ember-tetra");
        assertThat(second.slug()).isEqualTo("ember-tetra-2");
    }

    @Test
    void explicitSlugIsPreserved() {
        ProductResponse created = service.create(create("Ember Tetra", "My-Fish"));

        assertThat(created.slug()).isEqualTo("my-fish");
        assertThat(service.getBySlug("my-fish").productId()).isEqualTo(created.productId());
    }

    @Test
    void explicitDuplicateSlugRejected() {
        service.create(create("One", "shared-slug"));

        assertThatThrownBy(() -> service.create(create("Two", "shared-slug")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void invalidExplicitSlugRejected() {
        assertThatThrownBy(() -> service.create(create("One", "Not A Slug")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renameDoesNotChangeSlug() {
        ProductResponse created = service.create(create("Ember Tetra", null));

        ProductResponse updated = service.update(
                created.productId(), update("Scarlet Tetra", null));

        assertThat(updated.slug()).isEqualTo("ember-tetra");
    }

    @Test
    void explicitSlugChangeIsApplied() {
        ProductResponse created = service.create(create("Ember Tetra", null));

        ProductResponse updated = service.update(
                created.productId(), update(null, "scarlet-tetra"));

        assertThat(updated.slug()).isEqualTo("scarlet-tetra");
        assertThat(service.getBySlug("scarlet-tetra").productId()).isEqualTo(created.productId());
    }

    @Test
    void lookupBySlugIgnoresCase() {
        ProductResponse created = service.create(create("Ember Tetra", null));

        assertThat(service.getBySlug("EMBER-TETRA").productId()).isEqualTo(created.productId());
    }

    @Test
    void explicitSlugChangeRejectsCollision() {
        service.create(create("One", "taken-slug"));
        ProductResponse other = service.create(create("Two", "other-slug"));

        assertThatThrownBy(() -> service.update(
                other.productId(), update(null, "taken-slug")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void deletedProductIsNotReadableBySlug() {
        ProductResponse created = service.create(create("Ember Tetra", null));
        service.delete(created.productId());

        assertThatThrownBy(() -> service.getBySlug("ember-tetra"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suffixedSlugStaysWithinColumnLimit() {
        String longName = "a".repeat(255);
        ProductResponse first = service.create(create(longName, null));
        ProductResponse second = service.create(create(longName, null));

        assertThat(first.slug()).hasSize(255);
        assertThat(second.slug()).hasSizeLessThanOrEqualTo(255).endsWith("-2");
    }

    @Test
    void deletedProductSlugRemainsReserved() {
        ProductResponse created = service.create(create("Ember Tetra", null));
        service.delete(created.productId());

        ProductResponse replacement = service.create(create("Ember Tetra", null));

        assertThat(replacement.slug()).isEqualTo("ember-tetra-2");
    }

    @Test
    void generatedSlugSkipsReservedSearchRoute() {
        ProductResponse created = service.create(create("Search", null));

        assertThat(created.slug()).isEqualTo("search-2");
    }

    @Test
    void reservedExplicitSlugRejected() {
        assertThatThrownBy(() -> service.create(create("Search", "search")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repositorySaveGeneratesSlugWhenMissing() {
        Product product = repo.saveAndFlush(Product.builder()
                .name("Neon Tetra").description("d").build());

        assertThat(product.getSlug()).isEqualTo("neon-tetra");
    }
}
