package com.project.dacn.controller.shipping;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.integration.shipping.ghn.GhnClient;
import com.project.dacn.integration.shipping.ghn.dto.GhnProvince;
import com.project.dacn.integration.shipping.ghn.dto.GhnWard;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shipping/ghn")
@RequiredArgsConstructor
@Tag(name = "shipping")
public class ShippingMasterDataController {

    private final GhnClient ghnClient;

    @GetMapping("/provinces")
    public ApiResponse<List<GhnProvince>> provinces() {
        return ApiResponse.ok(ghnClient.getProvinces());
    }

    @GetMapping("/wards")
    public ApiResponse<List<GhnWard>> wards(@RequestParam int provinceId) {
        boolean knownProvince = ghnClient.getProvinces().stream()
                .anyMatch(province -> province.id() != null && province.id() == provinceId);
        if (!knownProvince) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(ghnClient.getWards(provinceId));
    }
}
