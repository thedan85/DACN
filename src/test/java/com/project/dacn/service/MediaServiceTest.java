package com.project.dacn.service;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.catalog.MediaCreateRequest;
import com.project.dacn.dto.request.catalog.MediaUpdateRequest;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.MediaRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Mock
    CatalogCache cache;

    @InjectMocks
    MediaService service;

    @Test
    void updateBumpsProductGenerationAfterCommit() {
        Media m = Media.builder().mediaId("m").url("u").build();
        when(repo.findByMediaId("m")).thenReturn(Optional.of(m));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.update("m", new MediaUpdateRequest("u2"));

        verify(cache).bumpAfterCommit("product");
    }

    @Test
    void deleteBumpsProductGenerationAfterCommit() {
        Media m = Media.builder().mediaId("m").url("u").build();
        when(repo.findByMediaId("m")).thenReturn(Optional.of(m));

        service.delete("m");

        verify(cache).bumpAfterCommit("product");
    }

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
                () -> service.listByProduct("nope", org.springframework.data.domain.Pageable.unpaged(),
                        false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listByProductFallsBackToVariantMediaWhenProductHasNoDirectMedia() {
        PageRequest page = PageRequest.of(0, 20);
        Product product = Product.builder().productId("p").status(ProductStatus.ACTIVE).build();
        Media media = Media.builder().mediaId("m").url("u").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));
        when(repo.findByProduct_ProductId("p", page)).thenReturn(Page.empty(page));
        when(repo.findByVariant_Product_ProductId("p", page))
                .thenReturn(new PageImpl<>(List.of(media), page, 1));

        var result = service.listByProduct("p", page, false);

        assertThat(result.getContent()).extracting(MediaResponse::mediaId).containsExactly("m");
    }

    @Test
    void listByProductKeepsDirectMediaAndDoesNotMixVariantMedia() {
        PageRequest page = PageRequest.of(0, 20);
        Product product = Product.builder().productId("p").status(ProductStatus.ACTIVE).build();
        Media media = Media.builder().mediaId("direct").url("u").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));
        when(repo.findByProduct_ProductId("p", page))
                .thenReturn(new PageImpl<>(List.of(media), page, 1));

        var result = service.listByProduct("p", page, false);

        assertThat(result.getContent()).extracting(MediaResponse::mediaId).containsExactly("direct");
        verify(repo, never()).findByVariant_Product_ProductId("p", page);
    }

    @Test
    void listByVariantMissingParentThrowsNotFound() {
        when(variantRepo.findByVariantId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(
                () -> service.listByVariant("nope", org.springframework.data.domain.Pageable.unpaged(),
                        false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void publicListOnlyQueriesVisibleMedia() {
        PageRequest page = PageRequest.of(0, 20);
        when(repo.findVisible(ProductStatus.ACTIVE, page)).thenReturn(Page.empty());

        service.list(page, false);

        verify(repo).findVisible(ProductStatus.ACTIVE, page);
        verify(repo, never()).findByDeletedFalse(any());
    }

    @Test
    void adminListQueriesAllMedia() {
        PageRequest page = PageRequest.of(0, 20);
        when(repo.findByDeletedFalse(page)).thenReturn(Page.empty());

        service.list(page, true);

        verify(repo).findByDeletedFalse(page);
        verify(repo, never()).findVisible(any(), any());
    }

    @Test
    void publicGetHidesMediaOfDraftProduct() {
        Media media = Media.builder().mediaId("m").url("u")
                .product(Product.builder().productId("p").status(ProductStatus.DRAFT).build())
                .build();
        when(repo.findByMediaId("m")).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.get("m", false))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.get("m", true).mediaId()).isEqualTo("m");
    }

    @Test
    void publicScopedListsHideDraftParents() {
        Product draft = Product.builder().productId("p").status(ProductStatus.DRAFT).build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.listByProduct("p",
                org.springframework.data.domain.Pageable.unpaged(), false))
                .isInstanceOf(ResourceNotFoundException.class);

        Variant variant = Variant.builder().variantId("v").product(draft).build();
        when(variantRepo.findByVariantId("v")).thenReturn(Optional.of(variant));
        assertThatThrownBy(() -> service.listByVariant("v",
                org.springframework.data.domain.Pageable.unpaged(), false))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
