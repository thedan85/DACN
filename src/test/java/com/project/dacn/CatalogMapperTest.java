package com.project.dacn;

import com.project.dacn.dto.request.AttributeValueCreateRequest;
import com.project.dacn.dto.request.MediaCreateRequest;
import com.project.dacn.dto.request.ProductCreateRequest;
import com.project.dacn.entity.Media;
import com.project.dacn.mapper.CatalogMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogMapperTest {

    private final CatalogMapper mapper = Mappers.getMapper(CatalogMapper.class);

    @Test
    void productCreateIgnoresBusinessIdAndCategory() {
        assertThat(mapper.toProduct(new ProductCreateRequest("P", "d", null, "cat-1")).getProductId())
                .isNull();
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
