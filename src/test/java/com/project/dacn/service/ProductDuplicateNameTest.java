package com.project.dacn.service;

import com.project.dacn.common.exception.DuplicateResourceException;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.request.catalog.ProductUpdateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.mapper.catalog.CatalogMapperImpl;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.service.catalog.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({ProductService.class, CatalogMapperImpl.class})
class ProductDuplicateNameTest {

    @Autowired
    private ProductService service;

    @Autowired
    private ProductRepository productRepo;

    private static ProductCreateRequest create(String name) {
        return new ProductCreateRequest(name, "desc", null, null, null);
    }

    @Test
    void nameIsNormalizedToLowerCase() {
        ProductResponse p = service.create(create("  Ember   TETRA  "));
        assertThat(p.name()).isEqualTo("ember tetra");
        assertThat(p.productId()).isEqualTo("PR_ember-tetra");
    }

    @Test
    void specialCharactersRejected() {
        assertThatThrownBy(() -> service.create(create("Cá Neon!!!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("letters, digits and spaces");
    }

    @Test
    void duplicateNameIgnoringCaseAndSpacingThrows409() {
        service.create(create("Ember Tetra"));

        assertThatThrownBy(() -> service.create(create("  ember   tetra ")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("product name already exists");
    }

    @Test
    void legacyIdWithUppercaseNameAlsoBlocksDuplicate() {
        productRepo.saveAndFlush(Product.builder()
                .productId("3f2a-uuid-legacy").name("Ember Tetra").description("legacy").build());

        assertThatThrownBy(() -> service.create(create("ember tetra")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("product name already exists");
    }

    @Test
    void softDeletedNameStillReserved() {
        ProductResponse p = service.create(create("Gone Fish"));
        service.delete(p.productId());

        assertThatThrownBy(() -> service.create(create("Gone Fish")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void renameToDuplicateThrows409() {
        service.create(create("Product A"));
        ProductResponse p2 = service.create(create("Product B"));

        assertThatThrownBy(() -> service.update(p2.productId(),
                new ProductUpdateRequest("Product A", null, null, null, null)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("product name already exists");
    }

    @Test
    void renameKeepingSameNameIsAllowed() {
        ProductResponse p = service.create(create("Product A"));

        ProductResponse updated = service.update(p.productId(),
                new ProductUpdateRequest("PRODUCT A", "new desc", null, null, null));

        assertThat(updated.name()).isEqualTo("product a");
        assertThat(updated.description()).isEqualTo("new desc");
    }

    @Test
    void nameLongerThanLimitRejected() {
        assertThatThrownBy(() -> service.create(create("a".repeat(201))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 200");
    }
}
