package com.project.dacn;

import com.project.dacn.dto.response.cart.CompatibilityResponse;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.entity.catalog.Category;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.ProductAttributeValue;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import com.project.dacn.service.cart.CompatibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompatibilityServiceTest {

    @Mock
    ProductAttributeValueRepository attributeRepository;

    CompatibilityService service;

    @BeforeEach
    void setUp() {
        service = new CompatibilityService(attributeRepository);
    }

    @Test
    void plantsOnlyReturnsInfoWithoutQueryingAttributes() {
        Product plant = product("PRD_PLANT", "Anubias",
                category("epiphyte", category("aquatic-plants", null)));

        CompatibilityResponse result = service.evaluate(List.of(item(plant)));

        assertThat(result.verdict()).isEqualTo(CompatibilityResponse.Verdict.INFO);
        assertThat(result.checks()).isEmpty();
        verifyNoInteractions(attributeRepository);
    }

    @Test
    void productWithoutCategoryIsExcluded() {
        Product unknown = product("PRD_X", "Mystery", null);

        CompatibilityResponse result = service.evaluate(List.of(item(unknown)));

        assertThat(result.verdict()).isEqualTo(CompatibilityResponse.Verdict.INFO);
        verifyNoInteractions(attributeRepository);
    }

    @Test
    void singleFishReturnsInfoWithoutQueryingAttributes() {
        Product tiger = fishProduct("PRD_1", "Tiger Barb");

        CompatibilityResponse result = service.evaluate(List.of(item(tiger)));

        assertThat(result.verdict()).isEqualTo(CompatibilityResponse.Verdict.INFO);
        assertThat(result.recommendedTankLitres()).isNull();
        verifyNoInteractions(attributeRepository);
    }

    @Test
    void deduplicatesVariantsOfTheSameProduct() {
        Product tiger = fishProduct("PRD_1", "Tiger Barb");
        Product cherry = fishProduct("PRD_2", "Cherry Barb");
        when(attributeRepository.findByProduct_ProductIdIn(argThat(ids ->
                ids != null && ids.size() == 2
                        && Set.copyOf(ids).equals(Set.of("PRD_1", "PRD_2")))))
                .thenReturn(List.of());

        service.evaluate(List.of(item(tiger), item(tiger), item(cherry)));

        verify(attributeRepository).findByProduct_ProductIdIn(argThat(ids ->
                ids != null && ids.size() == 2
                        && Set.copyOf(ids).equals(Set.of("PRD_1", "PRD_2"))));
    }

    @Test
    void mapsTraitsAndReturnsCalculatorVerdict() {
        Product tiger = fishProduct("PRD_1", "Tiger Barb");
        Product cherry = fishProduct("PRD_2", "Cherry Barb");
        when(attributeRepository.findByProduct_ProductIdIn(argThat(ids -> ids != null && ids.size() == 2)))
                .thenReturn(List.of(
                        attribute(tiger, "temperature", "24-28 °C"),
                        attribute(tiger, "ph", "6.0-7.5"),
                        attribute(tiger, "temperament", "Semi-aggressive"),
                        attribute(tiger, "diet", "Omnivore"),
                        attribute(tiger, "size", "5-6 cm"),
                        attribute(tiger, "minimum-tank-size", "75 L"),
                        attribute(cherry, "temperature", "20-26 °C"),
                        attribute(cherry, "ph", "6.0-7.5"),
                        attribute(cherry, "temperament", "Peaceful"),
                        attribute(cherry, "diet", "Omnivore"),
                        attribute(cherry, "size", "4-5 cm"),
                        attribute(cherry, "minimum-tank-size", "75 L")));

        CompatibilityResponse result = service.evaluate(List.of(item(tiger), item(cherry)));

        assertThat(result.verdict()).isEqualTo(CompatibilityResponse.Verdict.CAUTION);
        assertThat(result.checks())
                .anyMatch(check -> check.code().equals("TEMPERAMENT"));
        assertThat(result.recommendedTankLitres()).isEqualTo(75);
    }

    private Product fishProduct(String productId, String name) {
        return product(productId, name, category("barb", category("fish", null)));
    }

    private Product product(String productId, String name, Category category) {
        return Product.builder().productId(productId).name(name).description("d")
                .category(category).build();
    }

    private Category category(String id, Category parent) {
        return Category.builder().categoryId(id).name(id).slug(id).parent(parent).build();
    }

    private CartItem item(Product product) {
        Variant variant = Variant.builder().variantId("VAR_" + product.getProductId())
                .sku("SKU-" + product.getProductId()).product(product).build();
        return CartItem.builder().cartItemId("CI_" + product.getProductId())
                .variant(variant).quantity(1).build();
    }

    private ProductAttributeValue attribute(Product product, String attributeId, String value) {
        Attribute attribute = Attribute.builder().attributeId(attributeId).name(attributeId).build();
        AttributeValue attributeValue = AttributeValue.builder()
                .attribute(attribute).value(value).build();
        return ProductAttributeValue.builder()
                .product(product).attribute(attribute).attributeValue(attributeValue).build();
    }
}
