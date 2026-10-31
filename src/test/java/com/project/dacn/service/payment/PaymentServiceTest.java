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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void createUrlSignsOrderIdAndWholeVndAmount() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET,
                PaymentStatus.PENDING, "150000.00");
        when(orderRepository.findByOrderIdAndCustomer_Id("ORD_1", 7L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));

        String url = service.createVnpayUrl(7L, "ORD_1", "1.2.3.4").payUrl();

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(url).contains("vnp_TxnRef=ORD_1");
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
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET,
                PaymentStatus.CONFIRMED, "10.00");
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
    void getRejectsForeignOrder() {
        when(orderRepository.findByOrderIdAndCustomer_Id("ORD_1", 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L, "ORD_1"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(paymentRepository);
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
