package com.project.dacn;

import com.project.dacn.dto.request.catalog.AttributeValueCreateRequest;
import com.project.dacn.dto.request.catalog.MediaCreateRequest;
import com.project.dacn.dto.request.catalog.ProductCreateRequest;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.mapper.catalog.CatalogMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogMapperTest {

    private final CatalogMapper mapper = Mappers.getMapper(CatalogMapper.class);

    @Test
    void productCreateIgnoresBusinessIdAndCategory() {
        assertThat(mapper.toProduct(
                new ProductCreateRequest("P", "d", "detail", "cat-1", null)).getProductId())
                .isNull();
    }

    @Test
    void productResponseCarriesVariantPriceRange() {
        Product product = Product.builder()
                .productId("PRD_1").name("P").description("d").build();

        ProductResponse response = mapper.toProductResponse(
                product, new BigDecimal("179000"), new BigDecimal("199000"));

        assertThat(response.minPrice()).isEqualByComparingTo("179000");
        assertThat(response.maxPrice()).isEqualByComparingTo("199000");
    }

    @Test
    void mediaRequiresExactlyOneTarget() {
        assertThatThrownBy(() -> mapper.toMedia(new MediaCreateRequest("u", "p-1", "v-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.toMedia(new MediaCreateRequest("u", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        Media media = mapper.toMedia(new MediaCreateRequest("u", "p-1", null));
        assertThat(media.getMediaId()).isNull();
    }

    @Test
    void blankAttributeValueRejected() {
        assertThatThrownBy(() -> mapper.toAttributeValue(new AttributeValueCreateRequest("a-1", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
