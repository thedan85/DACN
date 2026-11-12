package com.project.dacn.integration.payment.vnpay;

import com.project.dacn.service.payment.PaymentService;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.OrderLifecycleService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class VnpayWebhookControllerTest {

    private static final VnpaySignatureService SIGNATURE =
            new VnpaySignatureService(props());

    private static final String FRONTEND_ORDERS = "http://localhost:3000/account/orders";

    private final PaymentService paymentService = mock(PaymentService.class);

    private final VnpayWebhookController controller =
            new VnpayWebhookController(SIGNATURE, paymentService, props());

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

        assertThat(controller.ipn(signedOutcome("24", "02"))).containsEntry("RspCode", "00");
    }

    @Test
    void ipnAcknowledgesRefundReviewWithoutClaimingConfirmedFulfilment() {
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.REFUND_REQUIRED);
        assertThat(controller.ipn(signedParams())).containsExactlyInAnyOrderEntriesOf(
                Map.of("RspCode", "00", "Message", "Received; refund review required"));
    }

    @Test
    void ipnAcknowledgesGenericDuplicateWithoutInferringOriginalOutcome() {
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.ALREADY_SETTLED);
        assertThat(controller.ipn(signedParams())).containsEntry("RspCode", "00");
    }

    @Test
    void returnOnlyResolvesPaymentIdentityAndRedirectsToOrder() {
        Map<String, String> params = signedParams();
        when(paymentService.orderIdForPayment("PAY_abc")).thenReturn("ORD_abc");

        var response = controller.returnPage(params);

        verify(paymentService).orderIdForPayment("PAY_abc");
        verifyNoMoreInteractions(paymentService);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(response.getHeaders().getLocation())
                .hasToString(FRONTEND_ORDERS + "/ORD_abc?payment=success");
    }

    @Test
    void returnRedirectsFailedPaymentToOrder() {
        Map<String, String> params = signedOutcome("24", "02");
        when(paymentService.orderIdForPayment("PAY_abc")).thenReturn("ORD_abc");

        var response = controller.returnPage(params);

        verify(paymentService).orderIdForPayment("PAY_abc");
        verifyNoMoreInteractions(paymentService);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(response.getHeaders().getLocation())
                .hasToString(FRONTEND_ORDERS + "/ORD_abc?payment=failed");
    }

    @Test
    void returnDoesNotSettleTamperedSignature() {
        Map<String, String> params = signedParams();
        params.put("vnp_Amount", "999");

        var response = controller.returnPage(params);

        verifyNoInteractions(paymentService);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(response.getHeaders().getLocation())
                .hasToString(FRONTEND_ORDERS + "?payment=invalid");
    }

    @Test
    void returnRedirectsUnknownPaymentToOrderList() {
        Map<String, String> params = signedParams();
        when(paymentService.orderIdForPayment("PAY_abc")).thenReturn(null);

        var response = controller.returnPage(params);

        verify(paymentService).orderIdForPayment("PAY_abc");
        verifyNoMoreInteractions(paymentService);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(response.getHeaders().getLocation())
                .hasToString(FRONTEND_ORDERS + "?payment=success");
    }

    @ParameterizedTest
    @CsvSource({"09,02", "10,02", "11,02", "12,02", "13,02", "24,02", "51,02", "65,02", "75,02", "79,02"})
    void ipnAcceptsOnlyDocumentedTerminalFailurePairs(String response, String status) {
        Map<String, String> params = signedOutcome(response, status);
        when(paymentService.handleIpn(params)).thenReturn(PaymentService.IpnResult.FAILED);

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertThat(controller.ipn(params)).containsExactlyInAnyOrderEntriesOf(
                Map.of("RspCode", "00", "Message", "Confirm Success"));
        verify(paymentService).handleIpn(params);
        verifyNoMoreInteractions(paymentService);
    }

    @ParameterizedTest(name = "invalid signed outcome {index}")
    @MethodSource("invalidOutcomes")
    void ipnRejectsInvalidSignedOutcomesWithoutSettlement(String response, String status) {
        Map<String, String> params = signedOutcome(response, status);
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.CONFIRMED);

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertThat(controller.ipn(params)).containsExactlyInAnyOrderEntriesOf(
                Map.of("RspCode", "99", "Message", "Invalid payment outcome"));
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest(name = "invalid signed reference {index}")
    @MethodSource("invalidReferences")
    void ipnRejectsInvalidSignedReferencesWithoutSettlement(String field, String value, String response, String status) {
        Map<String, String> params = signedOutcome(response, status);
        replaceAndSign(params, field, value);
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.CONFIRMED);

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertThat(controller.ipn(params)).containsExactlyInAnyOrderEntriesOf(
                Map.of("RspCode", "99", "Message", "Invalid payment reference"));
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest(name = "invalid signed merchant {index}")
    @MethodSource("invalidMerchants")
    void ipnRejectsAuthenticButUnconfiguredMerchant(String merchant) {
        Map<String, String> params = signedParams();
        replaceAndSign(params, "vnp_TmnCode", merchant);
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.CONFIRMED);

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertThat(controller.ipn(params)).containsExactlyInAnyOrderEntriesOf(
                Map.of("RspCode", "99", "Message", "Invalid merchant"));
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest(name = "unconfigured merchant {index}")
    @MethodSource("blankValues")
    void ipnRejectsMissingMerchantConfiguration(String merchant) {
        VnpayProperties properties = props();
        properties.setTmnCode(merchant);
        var unconfigured = new VnpayWebhookController(SIGNATURE, paymentService, properties);
        when(paymentService.handleIpn(any())).thenReturn(PaymentService.IpnResult.CONFIRMED);

        assertThat(unconfigured.ipn(signedParams())).containsExactlyInAnyOrderEntriesOf(
                Map.of("RspCode", "99", "Message", "Invalid merchant"));
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest(name = "invalid return outcome {index}")
    @MethodSource("invalidOutcomes")
    void returnRejectsInvalidSignedOutcomesWithoutSettlement(String response, String status) {
        Map<String, String> params = signedOutcome(response, status);

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertInvalidReturn(params);
    }

    @ParameterizedTest(name = "invalid return reference {index}")
    @MethodSource("invalidReferences")
    void returnRejectsInvalidSignedReferencesWithoutSettlement(String field, String value, String response, String status) {
        Map<String, String> params = signedOutcome(response, status);
        replaceAndSign(params, field, value);

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertInvalidReturn(params);
    }

    @ParameterizedTest(name = "invalid signed return amount {index}")
    @MethodSource("invalidAmounts")
    void returnRejectsInvalidSignedAmountsWithoutServiceCalls(String response, String status, String amount) {
        Map<String, String> params = signedOutcome(response, status);
        replaceAndSign(params, "vnp_Amount", amount);

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertInvalidReturn(params);
    }

    @ParameterizedTest(name = "return amount rejection before persistence {index}")
    @MethodSource("invalidAmounts")
    void returnRejectsInvalidSignedAmountsBeforePersistenceOrLifecycle(String response, String status, String amount) {
        PaymentRepository payments = mock(PaymentRepository.class);
        OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
        PaymentService adapter = new PaymentService(payments, null, null, null,
                new PaymentSettlementService(lifecycle));
        var actual = new VnpayWebhookController(SIGNATURE, adapter, props());
        Map<String, String> params = signedOutcome(response, status);
        replaceAndSign(params, "vnp_Amount", amount);

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        var redirect = actual.returnPage(params);

        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(redirect.getHeaders().getLocation()).hasToString(FRONTEND_ORDERS + "?payment=invalid");
        verifyNoInteractions(payments, lifecycle);
    }

    @ParameterizedTest(name = "invalid return merchant {index}")
    @MethodSource("invalidMerchants")
    void returnRejectsAuthenticButUnconfiguredMerchant(String merchant) {
        Map<String, String> params = signedParams();
        replaceAndSign(params, "vnp_TmnCode", merchant);

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertInvalidReturn(params);
    }

    @ParameterizedTest(name = "return unconfigured merchant {index}")
    @MethodSource("blankValues")
    void returnRejectsMissingMerchantConfiguration(String merchant) {
        VnpayProperties properties = props();
        properties.setTmnCode(merchant);
        var unconfigured = new VnpayWebhookController(SIGNATURE, paymentService, properties);

        var response = unconfigured.returnPage(signedParams());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(response.getHeaders().getLocation()).hasToString(FRONTEND_ORDERS + "?payment=invalid");
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest(name = "write-free signed rejection {index}")
    @MethodSource("invalidSignedEvidence")
    void invalidSignedEvidenceCannotReachPersistenceOrLifecycle(Map<String, String> params) {
        PaymentRepository payments = mock(PaymentRepository.class);
        OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
        PaymentService adapter = new PaymentService(payments, null, null, null,
                new PaymentSettlementService(lifecycle));
        var actual = new VnpayWebhookController(SIGNATURE, adapter, props());

        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        assertThat(actual.ipn(params)).containsEntry("RspCode", "99");
        assertThat(actual.returnPage(params).getHeaders().getLocation())
                .hasToString(FRONTEND_ORDERS + "?payment=invalid");
        verifyNoInteractions(payments, lifecycle);
    }

    @ParameterizedTest
    @CsvSource({"00,00,success,15000000", "24,02,failed,15000000",
            "00,00,success,15000000.00", "24,02,failed,15000000.00",
            "00,00,success,1.5E+7", "24,02,failed,1.5E+7",
            "00,00,success,+15000000", "24,02,failed,+15000000"})
    void returnWithRealPaymentAdapterOnlyReadsIdentity(String response, String status, String outcome, String amount) {
        PaymentRepository payments = mock(PaymentRepository.class);
        OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
        PaymentService adapter = new PaymentService(payments, null, null, null,
                new PaymentSettlementService(lifecycle));
        var actual = new VnpayWebhookController(SIGNATURE, adapter, props());
        when(payments.findByPaymentId("PAY_abc")).thenReturn(Optional.of(Payment.builder()
                .order(Order.builder().orderId("ORD_abc").build()).build()));

        Map<String, String> params = signedOutcome(response, status);
        replaceAndSign(params, "vnp_Amount", amount);
        assertThat(SIGNATURE.verifyParams(params)).isTrue();
        var redirect = actual.returnPage(params);

        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(redirect.getHeaders().getLocation()).hasToString(FRONTEND_ORDERS + "/ORD_abc?payment=" + outcome);
        verify(payments).findByPaymentId("PAY_abc");
        verifyNoMoreInteractions(payments);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void returnRejectsMissingSignatureWithoutSettlement() {
        Map<String, String> params = signedParams();
        params.remove("vnp_SecureHash");

        assertInvalidReturn(params);
    }

    private void assertInvalidReturn(Map<String, String> params) {
        var response = controller.returnPage(params);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(response.getHeaders().getLocation()).hasToString(FRONTEND_ORDERS + "?payment=invalid");
        verifyNoInteractions(paymentService);
    }

    static Stream<Arguments> invalidOutcomes() {
        return Stream.of(
                new String[]{"00", "02"}, new String[]{"24", "00"},
                new String[]{null, "00"}, new String[]{"00", null},
                new String[]{"", "00"}, new String[]{"00", ""},
                new String[]{" ", "00"}, new String[]{"00", "\t"},
                new String[]{"0", "00"}, new String[]{"00", "0"},
                new String[]{"000", "00"}, new String[]{"00", "000"},
                new String[]{"ab", "00"}, new String[]{"00", "ab"},
                new String[]{" 00", "00"}, new String[]{"00", "00 "},
                new String[]{"99", "02"}, new String[]{"00", "99"},
                new String[]{"07", "00"}, new String[]{"07", "02"},
                new String[]{"24", "01"}, new String[]{"24", "04"},
                new String[]{"24", "05"}, new String[]{"24", "06"},
                new String[]{"24", "07"}, new String[]{"24", "09"})
                .map(values -> Arguments.of((Object[]) values));
    }

    static Stream<Arguments> invalidReferences() {
        return Stream.of("00", "24").flatMap(response ->
                Stream.of("vnp_TxnRef", "vnp_TransactionNo").flatMap(field ->
                        blankValues().map(value -> Arguments.of(
                                field, value, response, "00".equals(response) ? "00" : "02"))));
    }

    static Stream<Arguments> invalidAmounts() {
        return Stream.of("00", "24").flatMap(response ->
                Stream.concat(blankValues(), Stream.of("not-money", "15000000.5", " 15000000", "1e-1"))
                        .map(amount -> Arguments.of(response, "00".equals(response) ? "00" : "02", amount)));
    }

    static Stream<String> invalidMerchants() {
        return Stream.concat(blankValues(), Stream.of("OTHER", "tmn01", " TMN01"));
    }

    static Stream<String> blankValues() {
        return Stream.of(null, "", " \t");
    }

    static Stream<Map<String, String>> invalidSignedEvidence() {
        Stream<Map<String, String>> outcomes = invalidOutcomes().map(arguments ->
                signedOutcome((String) arguments.get()[0], (String) arguments.get()[1]));
        Stream<Map<String, String>> references = invalidReferences().map(arguments -> {
            Object[] values = arguments.get();
            Map<String, String> params = signedOutcome((String) values[2], (String) values[3]);
            replaceAndSign(params, (String) values[0], (String) values[1]);
            return params;
        });
        Stream<Map<String, String>> merchants = invalidMerchants().map(merchant -> {
            Map<String, String> params = signedParams();
            replaceAndSign(params, "vnp_TmnCode", merchant);
            return params;
        });
        return Stream.concat(outcomes, Stream.concat(references, merchants));
    }

    private static Map<String, String> signedOutcome(String response, String status) {
        Map<String, String> params = signedParams();
        replaceAndSign(params, "vnp_ResponseCode", response);
        replaceAndSign(params, "vnp_TransactionStatus", status);
        return params;
    }

    private static void replaceAndSign(Map<String, String> params, String field, String value) {
        if (value == null) {
            params.remove(field);
        } else {
            params.put(field, value);
        }
        params.put("vnp_SecureHash", SIGNATURE.sign(SIGNATURE.canonical(params)));
    }

    private static Map<String, String> signedParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", "TMN01");
        params.put("vnp_Amount", "15000000");
        params.put("vnp_TxnRef", "PAY_abc");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionStatus", "00");
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
