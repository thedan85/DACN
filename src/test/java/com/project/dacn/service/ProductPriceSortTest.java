package com.project.dacn.service;

import com.project.dacn.common.constant.ProductSort;
import com.project.dacn.dto.request.catalog.ProductFilter;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.mapper.catalog.CatalogMapperImpl;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
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
@Import({ProductService.class, CatalogMapperImpl.class})
class ProductPriceSortTest {

    @Autowired
    private ProductService service;

    @Autowired
    private ProductRepository products;

    @Autowired
    private VariantRepository variants;

    private Product product(String name, String... prices) {
        Product product = products.save(Product.builder().name(name).description("d").build());
        int index = 1;
        for (String price : prices) {
            variants.save(Variant.builder().product(product)
                    .sku(name + "-" + index++)
                    .price(new BigDecimal(price)).stock(1).build());
        }
        return product;
    }

    private void deletedVariant(Product product, String price) {
        variants.save(Variant.builder().product(product)
                .sku(product.getName() + "-DELETED-" + price)
                .price(new BigDecimal(price)).stock(1).deleted(true).build());
    }

    private List<String> names(ProductSort sort, String minPrice, String maxPrice) {
        var result = service.search(new ProductFilter(null, null, null,
                minPrice == null ? null : new BigDecimal(minPrice),
                maxPrice == null ? null : new BigDecimal(maxPrice),
                sort), Pageable.unpaged());
        return result.getContent().stream().map(ProductResponse::name).toList();
    }

    @Test
    void priceAscSortsByLowestActiveVariantPrice() {
        product("Narrow", "20", "30");
        product("Wide", "10", "100");
        Product high = product("High", "200");
        deletedVariant(high, "1");

        assertThat(names(ProductSort.PRICE_ASC, null, null))
                .containsExactly("Wide", "Narrow", "High");
    }

    @Test
    void priceDescSortsByHighestActiveVariantPrice() {
        product("Narrow", "20", "30");
        Product wide = product("Wide", "10", "100");
        product("High", "200");
        deletedVariant(wide, "999");

        assertThat(names(ProductSort.PRICE_DESC, null, null))
                .containsExactly("High", "Wide", "Narrow");
    }

    @Test
    void priceAscPlacesProductsWithoutVariantsLast() {
        product("NoVariant");
        product("Low", "10");
        product("High", "100");

        assertThat(names(ProductSort.PRICE_ASC, null, null))
                .containsExactly("Low", "High", "NoVariant");
    }

    @Test
    void priceFilterUsesLowestActiveVariantPrice() {
        product("Cheap", "10");
        Product mid = product("Mid", "50", "100");
        product("Expensive", "200");
        deletedVariant(mid, "1");

        assertThat(names(null, "40", "120")).containsExactly("Mid");
    }

    @Test
    void priceFilterAppliesBothBoundsToLowestVariantPrice() {
        product("Wide", "50", "500");

        assertThat(names(null, "0", "100")).containsExactly("Wide");
        assertThat(names(null, "100", "1000")).isEmpty();
    }

    @Test
    void priceFilterExcludesProductsWithoutActiveVariants() {
        product("NoVariant");
        Product onlyDeleted = product("OnlyDeleted");
        deletedVariant(onlyDeleted, "50");
        product("Priced", "50");

        assertThat(names(null, "0", "100")).containsExactly("Priced");
    }

    @Test
    void priceSortSupportsPagingAndIgnoresPageableSort() {
        product("Low", "10");
        product("Mid", "20");
        product("High", "30");

        Pageable pageable = PageRequest.of(0, 2, Sort.by("price_asc"));
        var result = service.search(new ProductFilter(null, null, null,
                null, null, ProductSort.PRICE_ASC), pageable);

        assertThat(result.getContent().stream().map(ProductResponse::name).toList())
                .containsExactly("Low", "Mid");
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    void rejectsInvertedPriceRange() {
        assertThatThrownBy(() -> names(null, "100", "10"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativePriceFilter() {
        assertThatThrownBy(() -> names(null, "-1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
