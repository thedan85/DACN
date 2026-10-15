package com.project.dacn.service;

import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.response.catalog.MediaResponse;
import com.project.dacn.dto.response.catalog.UploadResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.integration.cloudinary.CloudinaryService;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.MediaService;
import com.project.dacn.service.catalog.ProductFolderResolver;
import com.project.dacn.service.catalog.UploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private static final long TEN_MB = 10L * 1024 * 1024;

    @Mock
    ProductRepository productRepo;

    @Mock
    VariantRepository variantRepo;

    @Mock
    MediaService mediaService;

    @Mock
    ProductFolderResolver folderResolver;

    @Mock
    CloudinaryService cloudinaryService;

    @InjectMocks
    UploadService service;

    private static MockMultipartFile jpg(String name, byte[] content) {
        return new MockMultipartFile("file", name, "image/jpeg", content);
    }

    @Test
    void uploadProductThumbnailSetsThumbnailAndReturnsFolder() {
        Product product = Product.builder().productId("congo-tetra").name("Congo Tetra").build();
        when(productRepo.findByProductId("congo-tetra")).thenReturn(Optional.of(product));
        when(folderResolver.resolve(product)).thenReturn("home/fish/tetra/congo-tetra");
        when(cloudinaryService.uploadAndGetUrl(any(byte[].class), anyString(), anyString()))
                .thenReturn("https://res.cloudinary.com/x/congo.jpg");

        UploadResponse response = service.uploadProductThumbnail("congo-tetra", jpg("congo.jpg", new byte[] {1, 2, 3}));

        assertThat(response.url()).isEqualTo("https://res.cloudinary.com/x/congo.jpg");
        assertThat(response.folder()).isEqualTo("home/fish/tetra/congo-tetra");
        assertThat(response.productId()).isEqualTo("congo-tetra");
        assertThat(product.getThumbnail()).isEqualTo("https://res.cloudinary.com/x/congo.jpg");
        verify(productRepo).save(product);
    }

    @Test
    void uploadVariantImageCreatesMediaInParentProductFolder() {
        Product product = Product.builder().productId("congo-tetra").name("Congo Tetra").build();
        Variant variant = Variant.builder().variantId("congo-tetra-albino").sku("FISH-CONGO-TETRA-ALBINO")
                .price(new java.math.BigDecimal("90000")).product(product).build();
        when(variantRepo.findByVariantId("congo-tetra-albino")).thenReturn(Optional.of(variant));
        when(folderResolver.resolve(product)).thenReturn("home/fish/tetra/congo-tetra");
        when(cloudinaryService.uploadAndGetUrl(any(byte[].class), anyString(), anyString()))
                .thenReturn("https://res.cloudinary.com/x/albino.jpg");
        when(mediaService.create(any())).thenReturn(new MediaResponse("m-1",
                "https://res.cloudinary.com/x/albino.jpg", null, "congo-tetra-albino", false,
                Instant.now(), Instant.now()));

        UploadResponse response = service.uploadVariantImage("congo-tetra-albino",
                jpg("albino.jpg", new byte[] {1, 2, 3}));

        assertThat(response.folder()).isEqualTo("home/fish/tetra/congo-tetra");
        assertThat(response.variantId()).isEqualTo("congo-tetra-albino");
        assertThat(response.mediaId()).isEqualTo("m-1");
        verify(mediaService).create(any());
    }

    @Test
    void emptyFileRejected() {
        Product product = Product.builder().productId("p").name("P").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.uploadProductThumbnail("p",
                new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizeFileRejected() {
        Product product = Product.builder().productId("p").name("P").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.uploadProductThumbnail("p", jpg("big.jpg", new byte[(int) (TEN_MB + 1)])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactlyTenMbAccepted() {
        Product product = Product.builder().productId("p").name("P").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));
        when(folderResolver.resolve(product)).thenReturn("home/p");
        when(cloudinaryService.uploadAndGetUrl(any(byte[].class), anyString(), anyString())).thenReturn("https://x/u.jpg");

        UploadResponse response = service.uploadProductThumbnail("p", jpg("edge.jpg", new byte[(int) TEN_MB]));

        assertThat(response.url()).isEqualTo("https://x/u.jpg");
    }

    @Test
    void wrongContentTypeRejected() {
        Product product = Product.builder().productId("p").name("P").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.uploadProductThumbnail("p",
                new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doubleExtensionSpoofRejected() {
        Product product = Product.builder().productId("p").name("P").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.uploadProductThumbnail("p", jpg("photo.JPG.exe", new byte[] {1})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uppercaseExtensionAccepted() {
        Product product = Product.builder().productId("p").name("P").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));
        when(folderResolver.resolve(product)).thenReturn("home/p");
        when(cloudinaryService.uploadAndGetUrl(any(byte[].class), anyString(), anyString())).thenReturn("https://x/u.jpg");

        UploadResponse response = service.uploadProductThumbnail("p",
                new MockMultipartFile("file", "PHOTO.JPG", "image/JPEG", new byte[] {1}));

        assertThat(response.url()).isEqualTo("https://x/u.jpg");
    }

    @Test
    void unknownProductThrowsNotFound() {
        when(productRepo.findByProductId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadProductThumbnail("nope", jpg("a.jpg", new byte[] {1})))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unknownVariantThrowsNotFound() {
        when(variantRepo.findByVariantId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadVariantImage("nope", jpg("a.jpg", new byte[] {1})))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cloudinaryFailurePerformsNoDbWrite() {
        Product product = Product.builder().productId("p").name("P").build();
        when(productRepo.findByProductId("p")).thenReturn(Optional.of(product));
        when(folderResolver.resolve(product)).thenReturn("home/p");
        when(cloudinaryService.uploadAndGetUrl(any(byte[].class), anyString(), anyString()))
                .thenThrow(new com.project.dacn.integration.cloudinary.CloudinaryException("boom"));

        assertThatThrownBy(() -> service.uploadProductThumbnail("p", jpg("a.jpg", new byte[] {1})))
                .isInstanceOf(com.project.dacn.integration.cloudinary.CloudinaryException.class);
        verify(productRepo, never()).save(any());
    }
}
