package com.project.dacn.integration.shipping.ghn;

import com.project.dacn.integration.shipping.ShippingException;
import com.project.dacn.integration.shipping.ShippingProvider;
import com.project.dacn.integration.shipping.ghn.dto.GhnCreateOrderRequest;
import com.project.dacn.integration.shipping.ghn.dto.GhnCreateOrderResponse;
import com.project.dacn.integration.shipping.ghn.dto.GhnMasterDataResponse;
import com.project.dacn.integration.shipping.ghn.dto.GhnProvince;
import com.project.dacn.integration.shipping.ghn.dto.GhnWard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class GhnClient implements ShippingProvider {

    private static final JsonMapper ERROR_MAPPER = JsonMapper.builder().build();

    private final RestClient ghnRestClient;
    private final GhnProperties properties;

    // shortcut: master data has no TTL or size bound; add a TTL cache if GHN starts changing it often.
    private volatile List<GhnProvince> provinces;
    private final ConcurrentHashMap<Integer, List<GhnWard>> wards = new ConcurrentHashMap<>();

    public GhnClient(RestClient ghnRestClient, GhnProperties properties) {
        this.ghnRestClient = ghnRestClient;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "GHN";
    }

    public List<GhnProvince> getProvinces() {
        requireConfigured();
        List<GhnProvince> cached = provinces;
        if (cached != null) {
            return cached;
        }
        try {
            GhnMasterDataResponse<List<GhnProvince>> response = ghnRestClient.get()
                    .uri("/v3/master-data/province/all?offset=0&limit=200")
                    .header("Token", properties.getToken())
                    .header("ShopId", properties.getShopId())
                    .retrieve()
                    .body(new ParameterizedTypeReference<GhnMasterDataResponse<List<GhnProvince>>>() {
                    });
            if (response == null || response.code() == null || response.code() != 200) {
                throw new ShippingException("GHN: " + (response == null ? "empty response" : response.message()));
            }
            List<GhnProvince> filtered = response.data() == null ? List.of() : response.data().stream()
                    .filter(province -> province.status() != null && province.status() == 1)
                    .toList();
            provinces = filtered;
            return filtered;
        } catch (RestClientResponseException ex) {
            throw new ShippingException(ghnErrorMessage(ex), ex);
        } catch (RestClientException ex) {
            throw new ShippingException("GHN request failed", ex);
        }
    }

    public List<GhnWard> getWards(int provinceId) {
        requireConfigured();
        List<GhnWard> cached = wards.get(provinceId);
        if (cached != null) {
            return cached;
        }
        try {
            GhnMasterDataResponse<List<GhnWard>> response = ghnRestClient.get()
                    .uri("/v3/master-data/ward/all-by-province-id?province_id={provinceId}&offset=0&limit=200",
                            provinceId)
                    .header("Token", properties.getToken())
                    .header("ShopId", properties.getShopId())
                    .retrieve()
                    .body(new ParameterizedTypeReference<GhnMasterDataResponse<List<GhnWard>>>() {
                    });
            if (response == null || response.code() == null || response.code() != 200) {
                throw new ShippingException("GHN: " + (response == null ? "empty response" : response.message()));
            }
            List<GhnWard> filtered = response.data() == null ? List.of() : response.data().stream()
                    .filter(ward -> ward.status() != null && ward.status() == 1)
                    .toList();
            wards.put(provinceId, filtered);
            return filtered;
        } catch (RestClientResponseException ex) {
            throw new ShippingException(ghnErrorMessage(ex), ex);
        } catch (RestClientException ex) {
            throw new ShippingException("GHN request failed", ex);
        }
    }

    public GhnClient.CreatedOrder createOrder(GhnCreateOrderRequest request) {
        requireConfigured();
        try {
            GhnCreateOrderResponse response = ghnRestClient.post()
                    .uri("/v2/shipping-order/create")
                    .header("Token", properties.getToken())
                    .header("ShopId", properties.getShopId())
                    .body(request)
                    .retrieve()
                    .body(GhnCreateOrderResponse.class);
            if (response == null || response.code() == null || response.code() != 200
                    || response.data() == null || response.data().orderCode() == null) {
                throw new ShippingException("GHN: " + (response == null ? "empty response" : response.message()));
            }
            log.info("Created GHN order {} with fee {}", response.data().orderCode(), response.data().totalFee());
            return new CreatedOrder(response.data().orderCode(), response.data().totalFee());
        } catch (RestClientResponseException ex) {
            throw new ShippingException(ghnErrorMessage(ex), ex);
        } catch (RestClientException ex) {
            throw new ShippingException("GHN request failed", ex);
        }
    }

    private String ghnErrorMessage(RestClientResponseException ex) {
        try {
            JsonNode node = ERROR_MAPPER.readTree(ex.getResponseBodyAsString());
            String message = node.path("message").asString(null);
            if (message != null && !message.isBlank()) {
                return "GHN: " + message;
            }
        } catch (RuntimeException ignored) {
            // The body was not the documented error envelope.
        }
        return "GHN request failed";
    }

    private void requireConfigured() {
        if (!StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getToken())
                || !StringUtils.hasText(properties.getShopId())) {
            throw new ShippingException("GHN is not configured");
        }
    }

    public record CreatedOrder(String orderCode, BigDecimal totalFee) {
    }
}
