package com.project.dacn;

import com.project.dacn.controller.cart.CartController;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import com.project.dacn.service.auth.CurrentCustomerService;
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

import static org.mockito.Mockito.verify;
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

    @MockitoBean
    private CurrentCustomerService currentCustomer;

    // JwtAuthenticationFilter is a Filter, so the slice instantiates it even with
    // addFilters = false; these mocks satisfy its constructor.
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private CartDetailResponse detail() {
        return new CartDetailResponse("cart-1", 42L, "Jane", List.of(),
                0, BigDecimal.ZERO, null, null);
    }

    @Test
    void getAutoCreatesCart() throws Exception {
        when(currentCustomer.requireCustomerId()).thenReturn(42L);
        when(service.getDetail(42L)).thenReturn(detail());

        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartId").value("cart-1"));
    }

    @Test
    void patchInvalidQtyIs400() throws Exception {
        mockMvc.perform(patch("/api/v1/cart/items/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clientHeaderCannotOverrideAuthenticatedCustomer() throws Exception {
        when(currentCustomer.requireCustomerId()).thenReturn(42L);
        when(service.getDetail(42L)).thenReturn(detail());

        mockMvc.perform(get("/api/v1/cart").header("X-Customer-Id", "999"))
                .andExpect(status().isOk());

        verify(service).getDetail(42L);
    }
}
