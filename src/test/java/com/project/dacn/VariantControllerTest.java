package com.project.dacn;

import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.controller.catalog.VariantController;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.mapper.catalog.CatalogMapper;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.VariantService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP validation/envelopes with the real revision-checking service; DB races have their own PG suite. */
class VariantControllerTest {
    private MockMvc mvc;
    private VariantRepository repo;
    private CatalogCache cache;
    private Variant row;

    @BeforeEach
    void setup() {
        repo = mock(VariantRepository.class);
        cache = mock(CatalogCache.class);
        VariantService service = new VariantService(repo, mock(ProductRepository.class),
                org.mapstruct.factory.Mappers.getMapper(CatalogMapper.class), cache);
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
        ReflectionTestUtils.setField(service, "transactionLockBounds", false);
        row = Variant.builder().variantId("v").sku("old").price(BigDecimal.TEN)
                .stock(7).stockRevision(3).build();
        when(repo.findByVariantIdForUpdate("v")).thenReturn(Optional.of(row));
        mvc = MockMvcBuilders.standaloneSetup(new VariantController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void missingRevisionOnPatchAndPutIs400WithoutAnyMetadataWrites() throws Exception {
        mvc.perform(patch("/api/v1/variants/v").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"new\",\"price\":1,\"stock\":7}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("expectedStockRevision is required when stock is supplied"));
        mvc.perform(put("/api/v1/variants/v").contentType(MediaType.APPLICATION_JSON).content("{\"stock\":7}"))
                .andExpect(status().isBadRequest());
        assertThat(row.getSku()).isEqualTo("old");
        verify(repo, never()).saveAndFlush(any());
        verifyNoInteractions(cache);
    }

    @Test
    void staleSameStockIs409AndLeavesMetadataUnchanged() throws Exception {
        mvc.perform(patch("/api/v1/variants/v").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"new\",\"price\":1,\"stock\":7,\"expectedStockRevision\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("resource is busy; refresh state before trying again"));
        assertThat(row.getSku()).isEqualTo("old");
        assertThat(row.getPrice()).isEqualByComparingTo("10");
        verify(repo, never()).saveAndFlush(any());
        verifyNoInteractions(cache);
    }

    @Test
    void currentSameStockReturnsRevisionWithoutAdvancingIt() throws Exception {
        mvc.perform(put("/api/v1/variants/v").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":7,\"expectedStockRevision\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stock").value(7))
                .andExpect(jsonPath("$.data.stockRevision").value(3));
        verify(cache).bumpAfterCommit("variant", "product");
    }

    @Test
    void metadataAndBodylessDeleteNeedNoRevision() throws Exception {
        mvc.perform(patch("/api/v1/variants/v").contentType(MediaType.APPLICATION_JSON).content("{\"sku\":\"new\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stockRevision").value(3));
        assertThat(row.getStock()).isEqualTo(7);
        mvc.perform(delete("/api/v1/variants/v")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(row.getDeleted()).isTrue();
        assertThat(row.getStockRevision()).isEqualTo(3);
    }

    @Test
    void negativeAndOverflowRevisionsAndInvalidStockAre400() throws Exception {
        for (String body : new String[]{
                "{\"stock\":7,\"expectedStockRevision\":-1}",
                "{\"stock\":-1,\"expectedStockRevision\":3}",
                "{\"stock\":2147483648,\"expectedStockRevision\":3}",
                "{\"stock\":7,\"expectedStockRevision\":9223372036854775808}",
                "{\"stock\":7,\"expectedStockRevision\":\"invalid\"}"}) {
            mvc.perform(patch("/api/v1/variants/v").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        verify(repo, never()).saveAndFlush(any());
    }
}
