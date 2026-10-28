package com.project.dacn.service;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.AttributeCreateRequest;
import com.project.dacn.dto.request.catalog.AttributeUpdateRequest;
import com.project.dacn.dto.request.catalog.AttributeValueCreateRequest;
import com.project.dacn.dto.request.catalog.AttributeValueUpdateRequest;
import com.project.dacn.dto.response.catalog.AttributeValueResponse;
import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.AttributeRepository;
import com.project.dacn.repository.catalog.AttributeValueRepository;
import com.project.dacn.service.catalog.AttributeService;
import com.project.dacn.service.catalog.AttributeValueService;
import com.project.dacn.service.catalog.CatalogCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttributeServiceTest {

    @Mock
    AttributeRepository attributeRepo;

    @Mock
    AttributeValueRepository valueRepo;

    @Mock
    CatalogMapper mapper;

    @Mock
    CatalogCache cache;

    @InjectMocks
    AttributeService attributeService;

    @InjectMocks
    AttributeValueService valueService;

    @Test
    void attributeWritesBumpProductGeneration() {
        when(attributeRepo.findByAttributeId("color")).thenReturn(Optional.of(
                Attribute.builder().attributeId("color").name("Color").build()));
        when(mapper.toAttribute(any())).thenReturn(Attribute.builder().attributeId("size").build());

        attributeService.create(new AttributeCreateRequest("Size"));
        attributeService.update("color", new AttributeUpdateRequest("Colour"));
        attributeService.delete("color");

        verify(cache, times(3)).bumpAfterCommit("product");
    }

    @Test
    void attributeValueWritesBumpProductGeneration() {
        AttributeValue blue = AttributeValue.builder().attributeValueId("blue").value("Blue").build();
        when(attributeRepo.findByAttributeId("color")).thenReturn(Optional.of(
                Attribute.builder().attributeId("color").name("Color").build()));
        when(mapper.toAttributeValue(any())).thenReturn(blue);
        when(valueRepo.findByAttributeValueId("blue")).thenReturn(Optional.of(blue));

        valueService.create(new AttributeValueCreateRequest("color", "Blue"));
        valueService.update("blue", new AttributeValueUpdateRequest("Navy"));
        valueService.delete("blue");

        verify(cache, times(3)).bumpAfterCommit("product");
    }

    @Test
    void attributeValueDeleteHardDeletesRow() {
        AttributeValue v = AttributeValue.builder().attributeValueId("v").value("Red").build();
        when(valueRepo.findByAttributeValueId("v")).thenReturn(Optional.of(v));
        valueService.delete("v");
        verify(valueRepo).delete(v);
    }

    @Test
    void attributeValueCreateRequiresAttribute() {
        when(attributeRepo.findByAttributeId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> valueService.create(new AttributeValueCreateRequest("nope", "Red")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void attributeDeleteSetsFlag() {
        Attribute a = Attribute.builder().attributeId("color").name("Color").build();
        when(attributeRepo.findByAttributeId("color")).thenReturn(Optional.of(a));
        attributeService.delete("color");
        assertThat(a.getDeleted()).isTrue();
    }

    @Test
    void listByAttributePaginated() {
        AttributeValue v = AttributeValue.builder().attributeValueId("v").value("Red").build();
        Page<AttributeValue> page = new PageImpl<>(List.of(v), Pageable.unpaged(), 1);
        when(attributeRepo.findByAttributeId("color"))
                .thenReturn(Optional.of(Attribute.builder().attributeId("color").name("Color").build()));
        when(valueRepo.findByAttribute_AttributeId("color", Pageable.unpaged())).thenReturn(page);
        when(mapper.toAttributeValueResponse(v)).thenReturn(
                new AttributeValueResponse("v", "color", "Color", "Red", null, null));
        PageResponse<AttributeValueResponse> result =
                valueService.listByAttribute("color", Pageable.unpaged());
        assertThat(result.getContent()).hasSize(1);
        verify(valueRepo).findByAttribute_AttributeId("color", Pageable.unpaged());
    }

    @Test
    void listByAttributeMissingParentThrowsNotFound() {
        when(attributeRepo.findByAttributeId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> valueService.listByAttribute("nope", Pageable.unpaged()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
