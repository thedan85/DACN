package com.project.dacn;

import com.project.dacn.controller.cart.CartController;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.service.cart.CartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

@WebMvcTest(CartController.class)
@AutoConfigureMockMvc(addFilters = false)
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService service;

    @Test
    void getAutoCreatesCart() throws Exception {
        var detail = new CartDetailResponse("cart-1", "c-1", "Jane", List.of(),
                0, BigDecimal.ZERO, null, null);
        when(service.getDetail("c-1")).thenReturn(detail);
        mockMvc.perform(get("/api/v1/cart").header("X-Customer-Id", "c-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartId").value("cart-1"));
    }

    @Test
    void patchInvalidQtyIs400() throws Exception {
        mockMvc.perform(patch("/api/v1/cart/items/i-1").header("X-Customer-Id", "c-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }
}
