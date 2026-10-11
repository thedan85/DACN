package com.project.dacn.mapper;

import com.project.dacn.dto.request.AttributeCreateRequest;
import com.project.dacn.dto.request.AttributeUpdateRequest;
import com.project.dacn.dto.request.AttributeValueCreateRequest;
import com.project.dacn.dto.request.AttributeValueUpdateRequest;
import com.project.dacn.dto.request.CategoryCreateRequest;
import com.project.dacn.dto.request.CategoryUpdateRequest;
import com.project.dacn.dto.request.MediaCreateRequest;
import com.project.dacn.dto.request.MediaUpdateRequest;
import com.project.dacn.dto.request.ProductAttributeValueCreateRequest;
import com.project.dacn.dto.request.ProductCreateRequest;
import com.project.dacn.dto.request.ProductUpdateRequest;
import com.project.dacn.dto.request.VariantAttributeValueCreateRequest;
import com.project.dacn.dto.request.VariantCreateRequest;
import com.project.dacn.dto.request.VariantUpdateRequest;
import com.project.dacn.dto.response.AttributeResponse;
import com.project.dacn.dto.response.AttributeValueResponse;
import com.project.dacn.dto.response.CategoryResponse;
import com.project.dacn.dto.response.MediaResponse;
import com.project.dacn.dto.response.ProductAttributeValueResponse;
import com.project.dacn.dto.response.ProductResponse;
import com.project.dacn.dto.response.VariantAttributeValueResponse;
import com.project.dacn.dto.response.VariantResponse;
import com.project.dacn.entity.Attribute;
import com.project.dacn.entity.AttributeValue;
import com.project.dacn.entity.Category;
import com.project.dacn.entity.Media;
import com.project.dacn.entity.Product;
import com.project.dacn.entity.ProductAttributeValue;
import com.project.dacn.entity.Variant;
import com.project.dacn.entity.VariantAttributeValue;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface CatalogMapper {

    // ---- Category ----
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "categoryId", ignore = true)
    @Mapping(target = "parent", ignore = true)
    @Mapping(target = "deleted", constant = "false")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Category toCategory(CategoryCreateRequest request);

    @Mapping(source = "parent.categoryId", target = "parentCategoryId")
    CategoryResponse toCategoryResponse(Category category);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "categoryId", ignore = true)
    @Mapping(target = "parent", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateCategory(CategoryUpdateRequest request, @MappingTarget Category category);

    // ---- Product ----
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "productId", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Product toProduct(ProductCreateRequest request);

    @Mapping(source = "category.categoryId", target = "categoryId")
    @Mapping(source = "category.name", target = "categoryName")
    ProductResponse toProductResponse(Product product);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "productId", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateProduct(ProductUpdateRequest request, @MappingTarget Product product);

    // ---- Attribute ----
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "attributeId", ignore = true)
    @Mapping(target = "deleted", constant = "false")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Attribute toAttribute(AttributeCreateRequest request);

    AttributeResponse toAttributeResponse(Attribute attribute);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "attributeId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateAttribute(AttributeUpdateRequest request, @MappingTarget Attribute attribute);

    // ---- AttributeValue ----
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "attributeValueId", ignore = true)
    @Mapping(target = "attribute", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    AttributeValue toAttributeValue(AttributeValueCreateRequest request);

    @AfterMapping
    default void checkAttributeValueNotBlank(AttributeValueCreateRequest request,
            @MappingTarget AttributeValue entity) {
        if (request.value() == null || request.value().trim().isEmpty()) {
            throw new IllegalArgumentException("attribute value must not be blank");
        }
    }

    @Mapping(source = "attribute.attributeId", target = "attributeId")
    @Mapping(source = "attribute.name", target = "attributeName")
    AttributeValueResponse toAttributeValueResponse(AttributeValue value);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "attributeValueId", ignore = true)
    @Mapping(target = "attribute", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateAttributeValue(AttributeValueUpdateRequest request, @MappingTarget AttributeValue value);

    // ---- Variant ----
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "variantId", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "deleted", constant = "false")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Variant toVariant(VariantCreateRequest request);

    @Mapping(source = "product.productId", target = "productId")
    @Mapping(source = "product.name", target = "productName")
    VariantResponse toVariantResponse(Variant variant);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "variantId", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateVariant(VariantUpdateRequest request, @MappingTarget Variant variant);

    // ---- Media ----
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "mediaId", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "variant", ignore = true)
    @Mapping(target = "deleted", constant = "false")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Media toMedia(MediaCreateRequest request);

    @AfterMapping
    default void checkMediaExactlyOneTarget(MediaCreateRequest request, @MappingTarget Media entity) {
        boolean hasProduct = request.productId() != null;
        boolean hasVariant = request.variantId() != null;
        if (hasProduct == hasVariant) {
            throw new IllegalArgumentException("media must reference exactly one of product or variant");
        }
    }

    @Mapping(source = "product.productId", target = "productId")
    @Mapping(source = "variant.variantId", target = "variantId")
    MediaResponse toMediaResponse(Media media);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "mediaId", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "variant", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateMedia(MediaUpdateRequest request, @MappingTarget Media media);

    // ---- Junctions ----
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "variantValueId", ignore = true)
    @Mapping(target = "variant", ignore = true)
    @Mapping(target = "attribute", ignore = true)
    @Mapping(target = "attributeValue", ignore = true)
    @Mapping(target = "attributeValueId", source = "attributeValueId")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    VariantAttributeValue toVariantAttributeValue(VariantAttributeValueCreateRequest request);

    @Mapping(source = "variant.variantId", target = "variantId")
    @Mapping(source = "attribute.attributeId", target = "attributeId")
    @Mapping(source = "attribute.name", target = "attributeName")
    @Mapping(source = "attributeValue.attributeValueId", target = "attributeValueId")
    @Mapping(source = "attributeValue.value", target = "attributeValue")
    VariantAttributeValueResponse toVariantAttributeValueResponse(VariantAttributeValue entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "productValueId", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "attribute", ignore = true)
    @Mapping(target = "attributeValue", ignore = true)
    @Mapping(target = "attributeValueId", source = "attributeValueId")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    ProductAttributeValue toProductAttributeValue(ProductAttributeValueCreateRequest request);

    @Mapping(source = "product.productId", target = "productId")
    @Mapping(source = "attribute.attributeId", target = "attributeId")
    @Mapping(source = "attribute.name", target = "attributeName")
    @Mapping(source = "attributeValue.attributeValueId", target = "attributeValueId")
    @Mapping(source = "attributeValue.value", target = "attributeValue")
    ProductAttributeValueResponse toProductAttributeValueResponse(ProductAttributeValue entity);
}
