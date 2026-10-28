package com.project.dacn.integration.payment.vnpay;

import com.project.dacn.integration.payment.PaymentSettlementService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VnpayWebhookControllerTest {

    private static final VnpaySignatureService SIGNATURE =
            new VnpaySignatureService(props());

    private final PaymentSettlementService settlement = mock(PaymentSettlementService.class);

    private final VnpayWebhookController controller =
            new VnpayWebhookController(SIGNATURE, settlement, props());

    @Test
    void ipnAcceptsValidSignature() {
        Map<String, String> params = signedParams();
        when(settlement.settle(any(), any(), anyBoolean(), any()))
                .thenReturn(PaymentSettlementService.Result.SETTLED);

        Map<String, String> body = controller.ipn(params);

        assertThat(body).containsEntry("RspCode", "00");
    }

    @Test
    void ipnAcknowledgesLatePaymentThatNeedsRefund() {
        Map<String, String> params = signedParams();
        when(settlement.settle(any(), any(), anyBoolean(), any()))
                .thenReturn(PaymentSettlementService.Result.REFUND_REQUIRED);

        assertThat(controller.ipn(params))
                .containsEntry("RspCode", "00")
                .containsEntry("Message", "Refund required");
    }

    @Test
    void ipnRejectsTamperedSignature() {
        Map<String, String> params = signedParams();
        params.put("vnp_Amount", "999");

        Map<String, String> body = controller.ipn(params);

        assertThat(body).containsEntry("RspCode", "97");
    }

    @Test
    void ipnRejectsMissingSignature() {
        Map<String, String> params = signedParams();
        params.remove("vnp_SecureHash");

        assertThat(controller.ipn(params)).containsEntry("RspCode", "99");
    }

    private static Map<String, String> signedParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", "TMN01");
        params.put("vnp_Amount", "15000000");
        params.put("vnp_TxnRef", "ORD_abc");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionNo", "123456");
        params.put("vnp_SecureHash", SIGNATURE.sign(SIGNATURE.canonical(params)));
        return params;
    }

    private static VnpayProperties props() {
        VnpayProperties properties = new VnpayProperties();
        properties.setTmnCode("TMN01");
        properties.setHashSecret("SECRETKEY");
        return properties;
    }
}
