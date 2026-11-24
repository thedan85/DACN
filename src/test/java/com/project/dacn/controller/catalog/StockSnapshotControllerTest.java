package com.project.dacn.controller.catalog;

import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.dto.request.catalog.StockSnapshotRequest;
import com.project.dacn.dto.response.catalog.StockSnapshotResponse;
import com.project.dacn.service.catalog.StockSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StockSnapshotControllerTest {

    @Mock
    private StockSnapshotService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StockSnapshotController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validSnapshotReturnsTheEnvelopeWithNoStore() throws Exception {
        when(service.snapshots(new StockSnapshotRequest(List.of("PRD_1"), List.of("VAR_1"))))
                .thenReturn(new StockSnapshotResponse(
                        List.of(new StockSnapshotResponse.ProductStock("PRD_1", 7L)),
                        List.of(new StockSnapshotResponse.VariantStock("VAR_1", "PRD_1", 7, 2L))));

        mvc.perform(post("/api/v1/stock/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"PRD_1\"],\"variantIds\":[\"VAR_1\"]}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.products[0]", aMapWithSize(2)))
                .andExpect(jsonPath("$.data.products[0].productId").value("PRD_1"))
                .andExpect(jsonPath("$.data.products[0].stock").value(7))
                .andExpect(jsonPath("$.data.variants[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.data.variants[0].variantId").value("VAR_1"))
                .andExpect(jsonPath("$.data.variants[0].productId").value("PRD_1"))
                .andExpect(jsonPath("$.data.variants[0].stock").value(7))
                .andExpect(jsonPath("$.data.variants[0].stockRevision").value(2));

        verify(service).snapshots(new StockSnapshotRequest(List.of("PRD_1"), List.of("VAR_1")));
    }

    @Test
    void malformedJsonReturnsTheExistingEnvelope() throws Exception {
        mvc.perform(post("/api/v1/stock/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("malformed request body"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(service);
    }

    @Test
    void missingArraysReturn400BeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/stock/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"PRD_1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mvc.perform(post("/api/v1/stock/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(service);
    }

    @Test
    void blankAndOverlongIdsReturn400BeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/stock/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\" \"],\"variantIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        String overlong = "x".repeat(256);
        var result = mvc.perform(post("/api/v1/stock/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"" + overlong + "\"],\"variantIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .doesNotContain(overlong);
        verifyNoInteractions(service);
    }

    @Test
    void serviceCountRejectionReturns400() throws Exception {
        when(service.snapshots(new StockSnapshotRequest(List.of(), List.of())))
                .thenThrow(new IllegalArgumentException("combined unique ids must be 1-100"));

        mvc.perform(post("/api/v1/stock/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[],\"variantIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void successDoesNotEmitACacheableBody() throws Exception {
        when(service.snapshots(new StockSnapshotRequest(List.of("PRD_1"), List.of())))
                .thenReturn(new StockSnapshotResponse(List.of(), List.of()));

        mvc.perform(post("/api/v1/stock/snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"PRD_1\"],\"variantIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andExpect(jsonPath("$.data.variants").isEmpty());
    }
}
