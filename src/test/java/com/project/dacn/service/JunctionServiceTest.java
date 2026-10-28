package com.project.dacn.service;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.catalog.ProductAttributeValueCreateRequest;
import com.project.dacn.dto.request.catalog.VariantAttributeValueCreateRequest;
import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.ProductAttributeValue;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.catalog.VariantAttributeValue;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.AttributeRepository;
import com.project.dacn.repository.catalog.AttributeValueRepository;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.ProductAttributeService;
import com.project.dacn.service.catalog.VariantAttributeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JunctionServiceTest {

    @Mock
    VariantAttributeValueRepository variantLinkRepo;

    @Mock
    ProductAttributeValueRepository productLinkRepo;

    @Mock
    VariantRepository variantRepo;

    @Mock
    ProductRepository productRepo;

    @Mock
    AttributeRepository attributeRepo;

    @Mock
    AttributeValueRepository valueRepo;

    @Mock
    CatalogMapper mapper;

    @Mock
    CatalogCache cache;

    @InjectMocks
    VariantAttributeService variantService;

    @InjectMocks
    ProductAttributeService productService;

    @Test
    void productAttributeWritesBumpProductGeneration() {
        Product product = Product.builder().productId("p").build();
        Attribute color = Attribute.builder().attributeId("color").name("Color").build();
        AttributeValue red = AttributeValue.builder().attributeValueId("red").value("Red").build();
        red.setAttribute(color);
        ProductAttributeValue link = ProductAttributeValue.builder().productValueId("pv").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));
        when(attributeRepo.findByAttributeId("color")).thenReturn(Optional.of(color));
        when(valueRepo.findByAttributeValueId("red")).thenReturn(Optional.of(red));
        when(mapper.toProductAttributeValue(any())).thenReturn(link);
        when(productLinkRepo.findByProductValueId("pv")).thenReturn(Optional.of(link));

        productService.create(new ProductAttributeValueCreateRequest("p", "color", "red"));
        productService.delete("pv");

        verify(cache, times(2)).bumpAfterCommit("product");
    }

    @Test
    void variantAttributeWritesBumpProductGeneration() {
        Variant variant = Variant.builder().variantId("v").sku("S").build();
        Attribute color = Attribute.builder().attributeId("color").name("Color").build();
        AttributeValue red = AttributeValue.builder().attributeValueId("red").value("Red").build();
        red.setAttribute(color);
        VariantAttributeValue link = VariantAttributeValue.builder().variantValueId("vv").build();
        when(variantRepo.findByVariantId("v")).thenReturn(Optional.of(variant));
        when(attributeRepo.findByAttributeId("color")).thenReturn(Optional.of(color));
        when(valueRepo.findByAttributeValueId("red")).thenReturn(Optional.of(red));
        when(mapper.toVariantAttributeValue(any())).thenReturn(link);
        when(variantLinkRepo.findByVariantValueId("vv")).thenReturn(Optional.of(link));

        variantService.create(new VariantAttributeValueCreateRequest("v", "color", "red"));
        variantService.delete("vv");

        verify(cache, times(2)).bumpAfterCommit("product");
    }

    @Test
    void variantLinkRequiresAllThreeSides() {
        when(variantRepo.findByVariantId("v")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> variantService.create(
                new VariantAttributeValueCreateRequest("v", "color", "color-red")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void noUpdateMethodOnJunctionService() {
        assertThat(Arrays.stream(VariantAttributeService.class.getMethods())
                .noneMatch(m -> m.getName().equals("update"))).isTrue();
        assertThat(Arrays.stream(ProductAttributeService.class.getMethods())
                .noneMatch(m -> m.getName().equals("update"))).isTrue();
    }

    @Test
    void mismatchedValueBelongsToAttributeRejected() {
        Variant v = Variant.builder().variantId("v").sku("S").build();
        Attribute color = Attribute.builder().attributeId("color").name("Color").build();
        Attribute size = Attribute.builder().attributeId("size").name("Size").build();
        AttributeValue sizeL = AttributeValue.builder().attributeValueId("size-l").value("L").build();
        sizeL.setAttribute(size);
        when(variantRepo.findByVariantId("v")).thenReturn(Optional.of(v));
        when(attributeRepo.findByAttributeId("color")).thenReturn(Optional.of(color));
        when(valueRepo.findByAttributeValueId("size-l")).thenReturn(Optional.of(sizeL));
        assertThatThrownBy(() -> variantService.create(
                new VariantAttributeValueCreateRequest("v", "color", "size-l")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void variantLinkDeleteHardDeletes() {
        VariantAttributeValue link = VariantAttributeValue.builder().variantValueId("vv").build();
        when(variantLinkRepo.findByVariantValueId("vv")).thenReturn(Optional.of(link));
        variantService.delete("vv");
        org.mockito.Mockito.verify(variantLinkRepo).delete(link);
    }

    @Test
    void listByVariantMissingParentThrowsNotFound() {
        when(variantRepo.findByVariantId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> variantService.listByVariant("nope", Pageable.unpaged()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listByProductMissingParentThrowsNotFound() {
        when(productRepo.findByProductId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> productService.listByProduct("nope", Pageable.unpaged()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
