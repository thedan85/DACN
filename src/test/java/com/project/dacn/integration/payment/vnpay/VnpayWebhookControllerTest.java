package com.project.dacn.integration.payment.vnpay;

import com.project.dacn.service.payment.PaymentService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VnpayWebhookControllerTest {

    private static final VnpaySignatureService SIGNATURE =
            new VnpaySignatureService(props());

    private final PaymentService paymentService = mock(PaymentService.class);

    private final VnpayWebhookController controller =
            new VnpayWebhookController(SIGNATURE, paymentService);

    @Test
    void ipnAcceptsValidSignatureAndSettles() {
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.CONFIRMED);

        Map<String, String> body = controller.ipn(signedParams());

        assertThat(body).containsEntry("RspCode", "00");
        verify(paymentService).handleIpn(any());
    }

    @Test
    void ipnRejectsTamperedSignature() {
        Map<String, String> params = signedParams();
        params.put("vnp_Amount", "999");

        Map<String, String> body = controller.ipn(params);

        assertThat(body).containsEntry("RspCode", "97");
        verify(paymentService, never()).handleIpn(any());
    }

    @Test
    void ipnRejectsMissingSignature() {
        Map<String, String> params = signedParams();
        params.remove("vnp_SecureHash");

        assertThat(controller.ipn(params)).containsEntry("RspCode", "99");
        verify(paymentService, never()).handleIpn(any());
    }

    @Test
    void ipnReportsUnknownOrder() {
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.ORDER_NOT_FOUND);

        assertThat(controller.ipn(signedParams())).containsEntry("RspCode", "01");
    }

    @Test
    void ipnReportsAmountMismatch() {
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.AMOUNT_MISMATCH);

        assertThat(controller.ipn(signedParams())).containsEntry("RspCode", "04");
    }

    @Test
    void ipnAcknowledgesRecordedFailure() {
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.FAILED);

        assertThat(controller.ipn(signedParams())).containsEntry("RspCode", "00");
    }

    private static Map<String, String> signedParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", "TMN01");
        params.put("vnp_Amount", "15000000");
        params.put("vnp_TxnRef", "PAY_abc");
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
