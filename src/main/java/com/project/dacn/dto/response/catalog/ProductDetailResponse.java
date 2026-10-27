package com.project.dacn.dto.response.catalog;

import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.response.engagement.ReviewResponse;

import java.util.List;

public record ProductDetailResponse(
        ProductResponse product,
        List<ProductAttributeValueResponse> attributes,
        List<VariantDetailResponse> variants,
        PageResponse<ReviewResponse> reviews) {
}
