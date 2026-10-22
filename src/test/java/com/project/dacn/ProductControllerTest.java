package com.project.dacn;

import com.project.dacn.common.constant.ProductSort;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.controller.catalog.ProductController;
import com.project.dacn.dto.request.catalog.ProductFilter;
import com.project.dacn.dto.response.catalog.ProductResponse;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import com.project.dacn.service.catalog.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService service;

    // JwtAuthenticationFilter is a Filter, so the slice instantiates it even with
    // addFilters = false; these mocks satisfy its constructor.
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void listBindsPublicFilterParams() throws Exception {
        when(service.search(any(ProductFilter.class), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 1, 5, 0, 0));

        mockMvc.perform(get("/api/v1/products")
                        .param("keyword", "tetra")
                        .param("category_id", "CAT_1")
                        .param("min_price", "10.00")
                        .param("max_price", "99.99")
                        .param("sort", "price_asc")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductFilter> filter = ArgumentCaptor.forClass(ProductFilter.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).search(filter.capture(), pageable.capture());

        assertThat(filter.getValue().keyword()).isEqualTo("tetra");
        assertThat(filter.getValue().categoryId()).isEqualTo("CAT_1");
        assertThat(filter.getValue().minPrice()).isEqualByComparingTo("10.00");
        assertThat(filter.getValue().maxPrice()).isEqualByComparingTo("99.99");
        assertThat(filter.getValue().sort()).isEqualTo(ProductSort.PRICE_ASC);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void listDefaultsToNewestSort() throws Exception {
        when(service.search(any(ProductFilter.class), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());

        ArgumentCaptor<ProductFilter> filter = ArgumentCaptor.forClass(ProductFilter.class);
        verify(service).search(filter.capture(), any(Pageable.class));
        assertThat(filter.getValue().sort()).isEqualTo(ProductSort.NEWEST);
    }
}
