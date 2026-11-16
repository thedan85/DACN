package com.project.dacn;

import com.project.dacn.integration.shipping.ShippingException;
import com.project.dacn.integration.shipping.ghn.GhnClient;
import com.project.dacn.integration.shipping.ghn.GhnProperties;
import com.project.dacn.integration.shipping.ghn.dto.GhnCreateOrderRequest;
import com.project.dacn.integration.shipping.ghn.dto.GhnProvince;
import com.project.dacn.integration.shipping.ghn.dto.GhnWard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class GhnClientTest {

    private MockRestServiceServer server;
    private GhnClient client;

    @BeforeEach
    void setUp() {
        GhnProperties properties = new GhnProperties();
        properties.setBaseUrl("https://ghn.test/shiip/public-api");
        properties.setToken("token");
        properties.setShopId("237833");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://ghn.test/shiip/public-api");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GhnClient(builder.build(), properties);
    }

    @Test
    void createOrderSendsTokenShopHeadersAndMapsResponse() {
        server.expect(requestTo("https://ghn.test/shiip/public-api/v2/shipping-order/create"))
                .andExpect(method(POST))
                .andExpect(header("Token", "token"))
                .andExpect(header("ShopId", "237833"))
                .andExpect(jsonPath("$.client_order_code").value("ORD_1"))
                .andExpect(jsonPath("$.service_type_id").value(2))
                .andExpect(jsonPath("$.payment_type_id").value(1))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success",
                         "data":{"order_code":"GHN-1","total_fee":22000}}
                        """, MediaType.APPLICATION_JSON));

        GhnClient.CreatedOrder created = client.createOrder(request());

        assertThat(created.orderCode()).isEqualTo("GHN-1");
        assertThat(created.totalFee()).isEqualByComparingTo("22000");
    }

    @Test
    void provincesFilterInactiveUnitsAndAreCached() {
        server.expect(requestTo("https://ghn.test/shiip/public-api/v3/master-data/province/all?offset=0&limit=200"))
                .andExpect(method(GET))
                .andExpect(header("Token", "token"))
                .andExpect(header("ShopId", "237833"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success","data":[
                          {"_id":1,"name":"Active","status":1},
                          {"_id":2,"name":"Disabled","status":2},
                          {"_id":3,"name":"Deleted","status":10}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getProvinces()).extracting(GhnProvince::id).containsExactly(1);
        assertThat(client.getProvinces()).extracting(GhnProvince::name).containsExactly("Active");
        assertThat(client.getProvinces()).hasSize(1);
        server.verify();
    }

    @Test
    void wardsFilterInactiveUnits() {
        server.expect(requestTo("https://ghn.test/shiip/public-api/v3/master-data/ward/all-by-province-id?province_id=1&offset=0&limit=200"))
                .andExpect(method(GET))
                .andExpect(header("Token", "token"))
                .andExpect(header("ShopId", "237833"))
                .andRespond(withSuccess("""
                        {"code":200,"message":"Success","data":[
                          {"_id":10,"name":"Ward A","status":1},
                          {"_id":11,"name":"Ward B","status":10}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getWards(1)).extracting(GhnWard::id).containsExactly(10);
        assertThat(client.getWards(1)).extracting(GhnWard::name).containsExactly("Ward A");
    }

    @Test
    void ghnErrorBodyBecomesShippingException() {
        server.expect(anything())
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":400,\"message\":\"bad ward\"}"));

        assertThatThrownBy(() -> client.createOrder(request()))
                .isInstanceOf(ShippingException.class)
                .hasMessageContaining("bad ward");
    }

    @Test
    void blankBaseUrlFailsBeforeAnyRequest() {
        GhnProperties properties = new GhnProperties();
        properties.setBaseUrl(" ");
        properties.setToken("token");
        properties.setShopId("237833");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://ghn.test/shiip/public-api");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GhnClient(builder.build(), properties);

        assertThatThrownBy(client::getProvinces)
                .isInstanceOf(ShippingException.class)
                .hasMessage("GHN is not configured");
        server.verify();
    }

    private GhnCreateOrderRequest request() {
        return new GhnCreateOrderRequest(
                1,
                "CHOXEMHANGKHONGTHU",
                "ORD_1",
                "Nguyen Van A",
                "0901234567",
                "123 Street",
                "Phuong Cho Quan",
                "Ho Chi Minh",
                true,
                "content",
                500,
                20,
                20,
                10,
                2,
                22000,
                List.of(new GhnCreateOrderRequest.Item("Item", "VAR_1", 1, 22000)));
    }
}
