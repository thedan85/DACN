package com.project.dacn.service.payment;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.dto.response.payment.PaymentVietQrResponse;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.integration.payment.vietqr.VietQrProperties;
import com.project.dacn.integration.payment.vietqr.VietQrUrlBuilder;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final PaymentMapper MAPPER = Mappers.getMapper(PaymentMapper.class);

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    OrderRepository orderRepository;

    @Mock
    PaymentSettlementService settlementService;

    PaymentService service;

    @BeforeEach
    void setUp() {
        VnpayProperties props = new VnpayProperties();
        props.setTmnCode("TMN01");
        props.setHashSecret("SECRETKEY");
        props.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        props.setReturnUrl("http://localhost:8080/api/v1/webhooks/vnpay/return");
        VnpaySignatureService signature = new VnpaySignatureService(props);

        VietQrProperties vietQrProperties = new VietQrProperties();
        vietQrProperties.setBankId("VCB");
        vietQrProperties.setAccountNo("0123456789");
        vietQrProperties.setAccountName("AQUA SHOP");

        service = new PaymentService(paymentRepository, orderRepository,
                new VnpayUrlBuilder(props, signature),
                new VietQrUrlBuilder(vietQrProperties), vietQrProperties,
                settlementService, MAPPER);
    }

    @Test
    void createUrlSignsPaymentIdAndWholeVndAmount() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET,
                PaymentStatus.PENDING, "150000.00");
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 7L))
                .thenReturn(Optional.of(payment));

        String url = service.createVnpayUrl(7L, "ORD_1", "1.2.3.4").payUrl();

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(url).contains("vnp_TxnRef=PAY_1");
        assertThat(url).contains("vnp_Amount=15000000");
        assertThat(url).contains("vnp_IpAddr=1.2.3.4");
        assertThat(url).contains("vnp_SecureHash=");
        verifyNoInteractions(orderRepository);
    }

    @Test
    void createUrlRejectsCodOrder() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.COD, PaymentStatus.PENDING, "10.00");
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 7L))
                .thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COD");
        verifyNoInteractions(orderRepository);
    }

    @Test
    void createUrlRejectsSettledPayment() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET,
                PaymentStatus.CONFIRMED, "10.00");
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 7L))
                .thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFIRMED");
        verifyNoInteractions(orderRepository);
    }

    @Test
    void createUrlRejectsForeignOrder() {
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 99L))
                .thenReturn(Optional.empty());
        when(orderRepository.findByOrderIdAndCustomer_Id("ORD_1", 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createVnpayUrl(99L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("order not found");
    }

    @Test
    void getRejectsForeignOrder() {
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 99L))
                .thenReturn(Optional.empty());
        when(orderRepository.findByOrderIdAndCustomer_Id("ORD_1", 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L, "ORD_1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("order not found");
    }

    @Test
    void createVietQrReturnsBankDetailsAndAmount() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.VIETQR,
                PaymentStatus.PENDING, "150000.00");
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 7L))
                .thenReturn(Optional.of(payment));

        PaymentVietQrResponse response = service.createVietQr(7L, "ORD_1");

        assertThat(response.qrUrl()).startsWith(
                "https://img.vietqr.io/image/VCB-0123456789-compact2.png?");
        assertThat(response.qrUrl()).contains("amount=150000", "addInfo=ORD_1");
        assertThat(response.bankId()).isEqualTo("VCB");
        assertThat(response.accountNo()).isEqualTo("0123456789");
        assertThat(response.accountName()).isEqualTo("AQUA SHOP");
        assertThat(response.amount()).isEqualByComparingTo("150000.00");
        assertThat(response.addInfo()).isEqualTo("ORD_1");
    }

    @Test
    void createVietQrRejectsNonVietQrPayment() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.COD,
                PaymentStatus.PENDING, "10.00");
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 7L))
                .thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.createVietQr(7L, "ORD_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIETQR");
    }

    @Test
    void createVietQrRejectsNonPendingOrder() {
        Order order = order("ORD_1", OrderStatus.CONFIRMED, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.VIETQR,
                PaymentStatus.PENDING, "10.00");
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 7L))
                .thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.createVietQr(7L, "ORD_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order is not pending");
    }

    @Test
    void createVnpayUrlRejectsVietQrOrder() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.VIETQR,
                PaymentStatus.PENDING, "10.00");
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 7L))
                .thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIETQR");
    }

    @Test
    void createVietQrRejectsForeignOrder() {
        when(paymentRepository.findWithOrderByOrderIdAndCustomerId("ORD_1", 99L))
                .thenReturn(Optional.empty());
        when(orderRepository.findByOrderIdAndCustomer_Id("ORD_1", 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createVietQr(99L, "ORD_1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("order not found");
    }

    @Test
    void confirmVietQrSettlesAndReturnsReloadedPayment() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment pending = payment("PAY_1", order, PaymentMethod.VIETQR,
                PaymentStatus.PENDING, "150000.00");
        Payment confirmed = payment("PAY_1", order("ORD_1", OrderStatus.CONFIRMED, "150000.00"),
                PaymentMethod.VIETQR, PaymentStatus.CONFIRMED, "150000.00");
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(pending));
        when(settlementService.settle(eq("ORD_1"), eq(new BigDecimal("150000.00")),
                eq(true), isNull())).thenReturn(PaymentSettlementService.Result.SETTLED);
        when(paymentRepository.findWithOrderByOrder_OrderId("ORD_1"))
                .thenReturn(Optional.of(confirmed));

        PaymentResponse response = service.confirmVietQrPayment("ORD_1");

        assertThat(response.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(response.method()).isEqualTo(PaymentMethod.VIETQR);
    }

    @Test
    void confirmVietQrReturnsRefundRequiredForCancelledOrder() {
        Order order = order("ORD_1", OrderStatus.CANCELLED, "10000.00");
        Payment pending = payment("PAY_1", order, PaymentMethod.VIETQR,
                PaymentStatus.PENDING, "10000.00");
        Payment refund = payment("PAY_1", order, PaymentMethod.VIETQR,
                PaymentStatus.REFUND_REQUIRED, "10000.00");
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(pending));
        when(settlementService.settle(eq("ORD_1"), eq(new BigDecimal("10000.00")),
                eq(true), isNull())).thenReturn(PaymentSettlementService.Result.REFUND_REQUIRED);
        when(paymentRepository.findWithOrderByOrder_OrderId("ORD_1"))
                .thenReturn(Optional.of(refund));

        assertThat(service.confirmVietQrPayment("ORD_1").status())
                .isEqualTo(PaymentStatus.REFUND_REQUIRED);
    }

    @Test
    void confirmVietQrIsIdempotentWhenAlreadySettled() {
        Order order = order("ORD_1", OrderStatus.CONFIRMED, "10000.00");
        Payment confirmed = payment("PAY_1", order, PaymentMethod.VIETQR,
                PaymentStatus.CONFIRMED, "10000.00");
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(confirmed));
        when(settlementService.settle(eq("ORD_1"), eq(new BigDecimal("10000.00")),
                eq(true), isNull())).thenReturn(PaymentSettlementService.Result.ALREADY_SETTLED);
        when(paymentRepository.findWithOrderByOrder_OrderId("ORD_1"))
                .thenReturn(Optional.of(confirmed));

        assertThat(service.confirmVietQrPayment("ORD_1").status())
                .isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void confirmVietQrRejectsNonVietQrPayment() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.COD,
                PaymentStatus.PENDING, "10.00");
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.confirmVietQrPayment("ORD_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIETQR");
        verifyNoInteractions(settlementService);
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
}
