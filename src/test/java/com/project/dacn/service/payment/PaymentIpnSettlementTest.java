package com.project.dacn.service.payment;

import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.ReservationReviewRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentIpnSettlementTest {
    @Mock PaymentRepository payments;
    @Mock PaymentSettlementService settlement;
    @InjectMocks PaymentService service;

    private Map<String, String> callback(String amount, boolean success) {
        return Map.of("vnp_TxnRef", "PAY_7", "vnp_Amount", amount,
                "vnp_ResponseCode", success ? "00" : "24", "vnp_TransactionNo", "TX_7");
    }
    @Test void resolvesPaymentBusinessIdAndRoutesSuccessThroughSharedSettlement() {
        when(payments.findOrderIdByPaymentId("PAY_7")).thenReturn(Optional.of("ORD_9"));
        when(settlement.settle("ORD_9", new BigDecimal("150000.00"), true, "TX_7"))
                .thenReturn(PaymentSettlementService.Result.SETTLED);
        assertThat(service.handleIpn(callback("15000000", true))).isEqualTo(PaymentService.IpnResult.CONFIRMED);
        verify(payments).findOrderIdByPaymentId("PAY_7");
        verifyNoMoreInteractions(payments);
    }
    @Test void failureAlsoUsesLockedEngineWithoutUnpaidPaidAtWrite() {
        when(payments.findOrderIdByPaymentId("PAY_7")).thenReturn(Optional.of("ORD_9"));
        when(settlement.settle("ORD_9", new BigDecimal("150000.00"), false, "TX_7"))
                .thenReturn(PaymentSettlementService.Result.SETTLED);
        assertThat(service.handleIpn(callback("15000000", false))).isEqualTo(PaymentService.IpnResult.FAILED);
        verifyNoMoreInteractions(payments);
    }
    @Test void receivedLateMoneyIsNotConfirmedFulfilment() {
        when(payments.findOrderIdByPaymentId("PAY_7")).thenReturn(Optional.of("ORD_9"));
        when(settlement.settle("ORD_9", new BigDecimal("150000.00"), true, "TX_7"))
                .thenReturn(PaymentSettlementService.Result.REFUND_REQUIRED);
        assertThat(service.handleIpn(callback("15000000", true)).name()).isEqualTo("REFUND_REQUIRED");
    }
    @Test void reviewExceptionPropagatesInsteadOfAcknowledgingSuccess() {
        when(payments.findOrderIdByPaymentId("PAY_7")).thenReturn(Optional.of("ORD_9"));
        when(settlement.settle("ORD_9", new BigDecimal("150000.00"), true, "TX_7"))
                .thenThrow(new ReservationReviewRequiredException("ORD_9"));
        assertThatThrownBy(() -> service.handleIpn(callback("15000000", true)))
                .isExactlyInstanceOf(ReservationReviewRequiredException.class);
    }
    @Test void malformedAmountDoesNotEnterLifecycle() {
        assertThat(service.handleIpn(callback("not-money", true))).isEqualTo(PaymentService.IpnResult.AMOUNT_MISMATCH);
        verifyNoInteractions(settlement);
    }
}
