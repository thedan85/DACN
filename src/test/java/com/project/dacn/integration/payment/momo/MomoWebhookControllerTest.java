package com.project.dacn.integration.payment.momo;

import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.service.order.ReservationReviewRequiredException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MomoWebhookControllerTest {
    private final MomoProperties properties = properties();
    private final MomoSignatureService signature = new MomoSignatureService(properties);
    private final PaymentSettlementService settlement = mock(PaymentSettlementService.class);
    private final MomoWebhookController controller = new MomoWebhookController(signature, properties, settlement);

    @Test void signedSuccessKeepsPublicSettlementContractAndAcknowledgement() {
        when(settlement.settle("ORD_1", new BigDecimal("30"), true, "TX1"))
                .thenReturn(PaymentSettlementService.Result.REFUND_REQUIRED);
        assertThat(controller.handleWebhook(payload("0")).getStatusCode().value()).isEqualTo(200);
    }
    @Test void signedFailureAlsoEntersSharedEngine() {
        when(settlement.settle("ORD_1", new BigDecimal("30"), false, "TX1"))
                .thenReturn(PaymentSettlementService.Result.SETTLED);
        assertThat(controller.handleWebhook(payload("1006")).getStatusCode().value()).isEqualTo(200);
        verify(settlement).settle("ORD_1", new BigDecimal("30"), false, "TX1");
    }
    @Test void tamperedSignatureCannotMutate() {
        Map<String, Object> payload = payload("0");
        payload.put("amount", "31");
        assertThat(controller.handleWebhook(payload).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(settlement);
    }
    @Test void reviewExceptionMustNotBecomeSuccess() {
        when(settlement.settle("ORD_1", new BigDecimal("30"), true, "TX1"))
                .thenThrow(new ReservationReviewRequiredException("ORD_1"));
        var response = controller.handleWebhook(payload("0"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).isEqualTo("reservation requires review");
        assertThat(response.getBody().getData()).isNull();
    }
    @Test void unknownOnlineOrderRetains404Envelope() {
        when(settlement.settle("ORD_1", new BigDecimal("30"), true, "TX1"))
                .thenReturn(PaymentSettlementService.Result.NOT_FOUND);
        assertThat(controller.handleWebhook(payload("0")).getStatusCode().value()).isEqualTo(404);
    }
    private Map<String, Object> payload(String result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("amount", "30"); values.put("orderId", "ORD_1");
        values.put("resultCode", result); values.put("transId", "TX1");
        values.put("signature", signature.sign("accessKey=test-access&amount=30&extraData=&message=&orderId=ORD_1"
                + "&orderInfo=&orderType=&partnerCode=&payType=&requestId=&responseTime=&resultCode=" + result + "&transId=TX1"));
        return values;
    }
    private static MomoProperties properties() {
        var properties = new MomoProperties();
        properties.setAccessKey("test-access"); properties.setSecretKey("test-only-secret");
        return properties;
    }
}
