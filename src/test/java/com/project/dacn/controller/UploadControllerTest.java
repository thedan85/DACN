package com.project.dacn.controller;

import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.controller.catalog.UploadController;
import com.project.dacn.dto.response.catalog.UploadResponse;
import com.project.dacn.integration.cloudinary.CloudinaryException;
import com.project.dacn.service.catalog.UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock
    UploadService uploadService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UploadController(uploadService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("file", "congo.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    @Test
    void uploadThumbnailReturnsUrl() throws Exception {
        when(uploadService.uploadProductThumbnail(eq("congo-tetra"), any())).thenReturn(
                new UploadResponse("https://x/congo.jpg", "home/fish/tetra/congo-tetra", "congo-tetra", null, null));

        mvc.perform(multipart("/api/uploads/products/congo-tetra/thumbnail").file(image()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").value("https://x/congo.jpg"))
                .andExpect(jsonPath("$.data.folder").value("home/fish/tetra/congo-tetra"));
    }

    @Test
    void uploadVariantImageReturnsMedia() throws Exception {
        when(uploadService.uploadVariantImage(eq("congo-tetra-albino"), any())).thenReturn(
                new UploadResponse("https://x/albino.jpg", "home/fish/tetra/congo-tetra",
                        "congo-tetra", "congo-tetra-albino", "m-1"));

        mvc.perform(multipart("/api/uploads/variants/congo-tetra-albino").file(image()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaId").value("m-1"));
    }

    @Test
    void unknownProductMapsTo404() throws Exception {
        when(uploadService.uploadProductThumbnail(eq("nope"), any()))
                .thenThrow(new ResourceNotFoundException("product not found: nope"));

        mvc.perform(multipart("/api/uploads/products/nope/thumbnail").file(image()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidFileMapsTo400() throws Exception {
        when(uploadService.uploadProductThumbnail(eq("p"), any()))
                .thenThrow(new IllegalArgumentException("file too large: max 10MB"));

        mvc.perform(multipart("/api/uploads/products/p/thumbnail").file(image()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cloudinaryFailureMapsTo502() throws Exception {
        when(uploadService.uploadProductThumbnail(eq("p"), any()))
                .thenThrow(new CloudinaryException("Cloudinary upload failed"));

        mvc.perform(multipart("/api/uploads/products/p/thumbnail").file(image()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void textFileRejectedByServiceAs400() throws Exception {
        MockMultipartFile text = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[] {1});
        when(uploadService.uploadProductThumbnail(eq("p"), any()))
                .thenThrow(new IllegalArgumentException("unsupported image type: text/plain"));

        mvc.perform(multipart("/api/uploads/products/p/thumbnail").file(text))
                .andExpect(status().isBadRequest());
    }
}
