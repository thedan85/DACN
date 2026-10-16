package com.project.dacn.service;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.catalog.VariantAttributeValueCreateRequest;
import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.catalog.VariantAttributeValue;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.AttributeRepository;
import com.project.dacn.repository.catalog.AttributeValueRepository;
import com.project.dacn.repository.catalog.ProductAttributeValueRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantAttributeValueRepository;
import com.project.dacn.repository.catalog.VariantRepository;
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

    @InjectMocks
    VariantAttributeService variantService;

    @InjectMocks
    ProductAttributeService productService;

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
