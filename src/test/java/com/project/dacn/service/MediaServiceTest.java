package com.project.dacn.service;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.catalog.MediaCreateRequest;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    MediaRepository repo;

    @Mock
    ProductRepository productRepo;

    @Mock
    VariantRepository variantRepo;

    @Spy
    CatalogMapper mapper = Mappers.getMapper(CatalogMapper.class);

    @InjectMocks
    MediaService service;

    @Test
    void createWithBothTargetsRejected() {
        assertThatThrownBy(() -> service.create(new MediaCreateRequest("u", "p-1", "v-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createWithNoTargetRejected() {
        assertThatThrownBy(() -> service.create(new MediaCreateRequest("u", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createResolvesMissingProduct404() {
        when(productRepo.findByProductId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(new MediaCreateRequest("u", "nope", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteSetsFlag() {
        Media m = Media.builder().mediaId("m").url("u").build();
        when(repo.findByMediaId("m")).thenReturn(Optional.of(m));
        service.delete("m");
        assertThat(m.getDeleted()).isTrue();
    }

    @Test
    void listByProductMissingParentThrowsNotFound() {
        when(productRepo.findByProductId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(
                () -> service.listByProduct("nope", org.springframework.data.domain.Pageable.unpaged()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listByVariantMissingParentThrowsNotFound() {
        when(variantRepo.findByVariantId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(
                () -> service.listByVariant("nope", org.springframework.data.domain.Pageable.unpaged()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
