package com.project.dacn.service;

import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.mapper.catalog.CatalogMapperImpl;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.service.catalog.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({ProductService.class, CatalogMapperImpl.class})
class ProductBusinessIdTest {

    @Autowired
    private ProductService service;

    @Autowired
    private ProductRepository productRepo;

    private static ProductCreateRequest create(String name) {
        return new ProductCreateRequest(name, "desc", null, null, null, null);
    }

    @Test
    void businessIdIsPrefixPlusSurrogateId() {
        ProductResponse created = service.create(create("  Ember   Tetra  "));

        assertThat(created.name()).isEqualTo("Ember Tetra");
        assertThat(created.slug()).isEqualTo("ember-tetra");
        assertThat(created.productId()).startsWith("PRD_");
        assertThat(created.productId())
                .isEqualTo("PRD_" + productRepo.findByProductId(created.productId())
                        .orElseThrow().getId());
    }

    @Test
    void duplicateNamesAreAllowed() {
        ProductResponse first = service.create(create("Ember Tetra"));
        ProductResponse second = service.create(create("ember tetra"));

        assertThat(second.productId()).isNotEqualTo(first.productId());
        assertThat(productRepo.findByProductId(second.productId())).isPresent();
    }
}
