package com.project.dacn.integration;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.project.dacn.integration.cloudinary.CloudinaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudinaryServiceTest {

    @Mock
    Cloudinary cloudinary;

    @Mock
    Uploader uploader;

    CloudinaryService service;

    @BeforeEach
    void setUp() {
        when(cloudinary.uploader()).thenReturn(uploader);
        service = new CloudinaryService(cloudinary);
    }

    @Test
    void uploadWithPublicIdSendsFolderAndPublicId() throws Exception {
        when(uploader.upload(any(), anyMap())).thenReturn(new HashMap<>(Map.of("secure_url", "https://x/y.jpg")));
        byte[] bytes = new byte[] {1, 2, 3};

        service.upload(bytes, "home/fish/tetra/congo-tetra", "congo-tetra-123");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map> params = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(uploader).upload(any(), params.capture());
        assertThat(params.getValue()).containsEntry("folder", "home/fish/tetra/congo-tetra");
        assertThat(params.getValue()).containsEntry("public_id", "congo-tetra-123");
        assertThat(params.getValue()).containsEntry("overwrite", false);
    }

    @Test
    void twoArgUploadOmitsPublicId() throws Exception {
        when(uploader.upload(any(), anyMap())).thenReturn(new HashMap<>(Map.of("secure_url", "https://x/y.jpg")));

        service.upload(new byte[] {1}, "home/fish/tetra/congo-tetra");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map> params = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(uploader).upload(any(), params.capture());
        assertThat(params.getValue()).containsEntry("folder", "home/fish/tetra/congo-tetra");
        assertThat(params.getValue()).doesNotContainKey("public_id");
    }

    @Test
    void uploadAndGetUrlReturnsSecureUrl() throws Exception {
        when(uploader.upload(any(), anyMap())).thenReturn(new HashMap<>(Map.of("secure_url", "https://x/y.jpg")));

        String url = service.uploadAndGetUrl(new byte[] {1}, "home/fish/tetra/congo-tetra", "congo-tetra-123");

        assertThat(url).isEqualTo("https://x/y.jpg");
    }
}
