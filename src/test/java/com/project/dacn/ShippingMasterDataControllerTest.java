package com.project.dacn;

import com.project.dacn.controller.shipping.ShippingMasterDataController;
import com.project.dacn.integration.shipping.ghn.GhnClient;
import com.project.dacn.integration.shipping.ghn.dto.GhnProvince;
import com.project.dacn.integration.shipping.ghn.dto.GhnWard;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.security.CustomUserDetailsService;
import com.project.dacn.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShippingMasterDataController.class)
@AutoConfigureMockMvc(addFilters = false)
class ShippingMasterDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GhnClient ghnClient;

    // JwtAuthenticationFilter is a Filter, so the slice instantiates it even with
    // addFilters = false; these mocks satisfy its constructor.
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void provincesReturnsActiveUnitsWithoutStatus() throws Exception {
        when(ghnClient.getProvinces())
                .thenReturn(List.of(new GhnProvince(1000001, "Hồ Chí Minh", 1)));

        mockMvc.perform(get("/api/v1/shipping/ghn/provinces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data[0].id").value(1000001))
                .andExpect(jsonPath("$.data[0].name").value("Hồ Chí Minh"))
                .andExpect(jsonPath("$.data[0].status").doesNotExist());
    }

    @Test
    void wardsReturnsWardsForProvince() throws Exception {
        when(ghnClient.getProvinces())
                .thenReturn(List.of(new GhnProvince(1000001, "Hồ Chí Minh", 1)));
        when(ghnClient.getWards(1000001))
                .thenReturn(List.of(new GhnWard(210001, "Phường Chợ Quán", 1)));

        mockMvc.perform(get("/api/v1/shipping/ghn/wards").param("provinceId", "1000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Phường Chợ Quán"))
                .andExpect(jsonPath("$.data[0].status").doesNotExist());
    }

    @Test
    void wardsReturnsEmptyListForUnknownProvinceWithoutCallingGhnWards() throws Exception {
        when(ghnClient.getProvinces())
                .thenReturn(List.of(new GhnProvince(1000001, "Hồ Chí Minh", 1)));

        mockMvc.perform(get("/api/v1/shipping/ghn/wards").param("provinceId", "9999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());

        verify(ghnClient, never()).getWards(anyInt());
    }
}
