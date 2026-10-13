package com.project.dacn;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.catalog.AttributeValueCreateRequest;
import com.project.dacn.dto.response.catalog.AttributeValueResponse;
import com.project.dacn.entity.catalog.Attribute;
import com.project.dacn.entity.catalog.AttributeValue;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.AttributeRepository;
import com.project.dacn.repository.catalog.AttributeValueRepository;
import com.project.dacn.service.catalog.AttributeService;
import com.project.dacn.service.catalog.AttributeValueService;
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

    @InjectMocks
    AttributeService attributeService;

    @InjectMocks
    AttributeValueService valueService;

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
        when(valueRepo.findByAttribute_AttributeId("color", Pageable.unpaged())).thenReturn(page);
        when(mapper.toAttributeValueResponse(v)).thenReturn(
                new AttributeValueResponse("v", "color", "Color", "Red", null, null));
        PageResponse<AttributeValueResponse> result =
                valueService.listByAttribute("color", Pageable.unpaged());
        assertThat(result.getContent()).hasSize(1);
        verify(valueRepo).findByAttribute_AttributeId("color", Pageable.unpaged());
    }
}
