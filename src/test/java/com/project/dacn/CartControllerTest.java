package com.project.dacn;

import com.project.dacn.controller.cart.CartController;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.security.JwtAuthenticationFilter;
import com.project.dacn.service.cart.CartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CartController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService service;

    @Test
    void getAutoCreatesCart() throws Exception {
        var detail = new CartDetailResponse("cart-1", 42L, "Jane", List.of(),
                0, BigDecimal.ZERO, null, null);
        when(service.getDetail(42L)).thenReturn(detail);
        mockMvc.perform(get("/api/v1/cart").header("X-Customer-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartId").value("cart-1"));
    }

    @Test
    void patchInvalidQtyIs400() throws Exception {
        mockMvc.perform(patch("/api/v1/cart/items/7").header("X-Customer-Id", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonNumericCustomerHeaderIs400() throws Exception {
        mockMvc.perform(get("/api/v1/cart").header("X-Customer-Id", "CUS_x"))
                .andExpect(status().isBadRequest());
    }
}
