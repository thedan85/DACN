package com.project.dacn.dto.response.catalog;

import java.math.BigDecimal;
import java.util.List;

public record VariantDetailResponse(
        String variantId,
        String sku,
        BigDecimal price,
        Integer stock,
        List<VariantAttributeValueResponse> attributes,
        List<MediaResponse> media) {
}
