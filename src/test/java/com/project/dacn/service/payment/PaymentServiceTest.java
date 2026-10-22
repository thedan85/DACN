package com.project.dacn.service.payment;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.payment.vnpay.VnpayProperties;
import com.project.dacn.integration.payment.vnpay.VnpaySignatureService;
import com.project.dacn.integration.payment.vnpay.VnpayUrlBuilder;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final PaymentMapper MAPPER = Mappers.getMapper(PaymentMapper.class);

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    OrderRepository orderRepository;

    PaymentService service;

    @BeforeEach
    void setUp() {
        VnpayProperties props = new VnpayProperties();
        props.setTmnCode("TMN01");
        props.setHashSecret("SECRETKEY");
        props.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        props.setReturnUrl("http://localhost:8080/api/v1/webhooks/vnpay/return");
        VnpaySignatureService signature = new VnpaySignatureService(props);
        service = new PaymentService(paymentRepository, orderRepository,
                new VnpayUrlBuilder(props, signature), MAPPER);
    }

    @Test
    void createUrlSignsPaymentIdAndWholeVndAmount() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET,
                PaymentStatus.PENDING, "150000.00");
        when(orderRepository.findByOrderIdAndCustomer_Id("ORD_1", 7L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        String url = service.createVnpayUrl(7L, "ORD_1", "1.2.3.4").payUrl();

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(url).contains("vnp_TxnRef=PAY_1");
        assertThat(url).contains("vnp_Amount=15000000");
        assertThat(url).contains("vnp_IpAddr=1.2.3.4");
        assertThat(url).contains("vnp_SecureHash=");
    }

    @Test
    void createUrlRejectsCodOrder() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.COD, PaymentStatus.PENDING, "10.00");
        when(orderRepository.findByOrderIdAndCustomer_Id("ORD_1", 7L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COD");
    }

    @Test
    void createUrlRejectsSettledPayment() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.CONFIRMED, "10.00");
        when(orderRepository.findByOrderIdAndCustomer_Id("ORD_1", 7L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    void createUrlRejectsForeignOrder() {
        when(orderRepository.findByOrderIdAndCustomer_Id("ORD_1", 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createVnpayUrl(99L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void ipnConfirmsPaymentAndOrder() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "150000.00");
        when(paymentRepository.findByPaymentId("PAY_1")).thenReturn(Optional.of(payment));
        when(paymentRepository.settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.CONFIRMED), eq("TX123"), any(Instant.class))).thenReturn(1);

        PaymentService.IpnResult result = service.handleIpn(ipn("PAY_1", "15000000", "00", "TX123"));

        assertThat(result).isEqualTo(PaymentService.IpnResult.CONFIRMED);
        verify(orderRepository).updateStatus("ORD_1", OrderStatus.PENDING, OrderStatus.CONFIRMED);
    }

    @Test
    void concurrentDuplicateIpnDoesNotDoubleSettle() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment pending = payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "150000.00");
        Payment confirmed = payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.CONFIRMED, "150000.00");
        when(paymentRepository.findByPaymentId("PAY_1"))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(confirmed));
        when(paymentRepository.settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.CONFIRMED), eq("TX999"), any(Instant.class))).thenReturn(0);

        PaymentService.IpnResult result = service.handleIpn(ipn("PAY_1", "15000000", "00", "TX999"));

        assertThat(result).isEqualTo(PaymentService.IpnResult.ALREADY_CONFIRMED);
        verify(orderRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void ipnMarksFailure() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "150000.00");
        when(paymentRepository.findByPaymentId("PAY_1")).thenReturn(Optional.of(payment));
        when(paymentRepository.settle(eq("PAY_1"), eq(PaymentStatus.PENDING),
                eq(PaymentStatus.FAILED), eq(null), any(Instant.class))).thenReturn(1);

        PaymentService.IpnResult result = service.handleIpn(ipn("PAY_1", "15000000", "24", "TX1"));

        assertThat(result).isEqualTo(PaymentService.IpnResult.FAILED);
        verify(orderRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void ipnRejectsAmountMismatch() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "150000.00");
        when(paymentRepository.findByPaymentId("PAY_1")).thenReturn(Optional.of(payment));

        PaymentService.IpnResult result = service.handleIpn(ipn("PAY_1", "100", "00", "TX1"));

        assertThat(result).isEqualTo(PaymentService.IpnResult.AMOUNT_MISMATCH);
        verify(paymentRepository, never()).settle(any(), any(), any(), any(), any());
    }

    @Test
    void ipnRejectsUnknownTxnRef() {
        when(paymentRepository.findByPaymentId("PAY_X")).thenReturn(Optional.empty());

        assertThat(service.handleIpn(ipn("PAY_X", "15000000", "00", "TX1")))
                .isEqualTo(PaymentService.IpnResult.ORDER_NOT_FOUND);
    }

    private static Order order(String orderId, OrderStatus status, String total) {
        return Order.builder()
                .orderId(orderId)
                .customer(Customer.builder().id(7L).customerId("cust").name("C").phone("090").build())
                .status(status)
                .total(new BigDecimal(total))
                .address("addr").recipientName("C").phone("090")
                .build();
    }

    private static Payment payment(String paymentId, Order order, PaymentMethod method,
                                   PaymentStatus status, String amount) {
        return Payment.builder()
                .paymentId(paymentId)
                .order(order)
                .method(method)
                .status(status)
                .amount(new BigDecimal(amount))
                .build();
    }

    private static Map<String, String> ipn(String txnRef, String amount, String responseCode, String txnNo) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_Amount", amount);
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionNo", txnNo);
        return params;
    }
}
