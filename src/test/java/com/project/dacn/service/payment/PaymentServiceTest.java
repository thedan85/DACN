package com.project.dacn.service.payment;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.payment.vnpay.VnpayProperties;
import com.project.dacn.integration.payment.vnpay.VnpaySignatureService;
import com.project.dacn.integration.payment.vnpay.VnpayUrlBuilder;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.service.order.ReservationPolicy;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final PaymentMapper MAPPER = Mappers.getMapper(PaymentMapper.class);

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    OrderRepository orderRepository;

    @Mock OrderItemRepository orderItemRepository;
    @Mock DatabaseClock clock;
    @Mock EntityManager entityManager;
    @Mock PaymentSettlementService settlementService;
    @Spy ReservationPolicy reservationPolicy = new ReservationPolicy(true, false, false);
    private final VnpaySignatureService signature = new VnpaySignatureService(props());
    @Spy VnpayUrlBuilder builder = new VnpayUrlBuilder(props(), signature);
    @Spy PaymentMapper mapper = MAPPER;
    @InjectMocks PaymentService service;

    private static final Instant DEADLINE = Instant.parse("2026-11-13T17:01:02Z");

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
        lenient().when(entityManager.unwrap(org.hibernate.Session.class)).thenReturn(mock(org.hibernate.Session.class));
        lenient().when(clock.now()).thenReturn(DEADLINE.minusSeconds(1));
        lenient().when(orderItemRepository.findStockReleaseTargetsByOrderId("ORD_1"))
                .thenReturn(List.of(mock(OrderItemRepository.StockReleaseTarget.class)));
    }

    private static VnpayProperties props() {
        VnpayProperties props = new VnpayProperties();
        props.setTmnCode("TMN01");
        props.setHashSecret("SECRETKEY");
        props.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        props.setReturnUrl("http://localhost:8080/api/v1/webhooks/vnpay/return");
        return props;
    }

    @Test
    void createUrlSignsPaymentIdAndWholeVndAmount() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET,
                PaymentStatus.PENDING, "150000.00");
        eligible(order, payment);

        String url = service.createVnpayUrl(7L, "ORD_1", "1.2.3.4").payUrl();

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(url).contains("vnp_TxnRef=PAY_1");
        assertThat(url).contains("vnp_Amount=15000000");
        assertThat(url).contains("vnp_IpAddr=1.2.3.4");
        assertThat(url).contains("vnp_SecureHash=");
        assertThat(signature.verifyParams(query(url))).isTrue();
        assertThat(query(url)).containsEntry("vnp_ExpireDate", "20261114000102");
        noWrites();
    }

    @Test
    void createUrlRejectsCodOrder() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.COD, PaymentStatus.PENDING, "10.00");
        eligible(order, payment);

        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COD");
        noWrites();
    }

    @Test
    void createUrlRejectsSettledPayment() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10.00");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET,
                PaymentStatus.CONFIRMED, "10.00");
        eligible(order, payment);

        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFIRMED");
        noWrites();
    }

    @Test
    void createUrlRejectsForeignOrder() {
        when(orderRepository.findWithLockByOrderId("ORD_1"))
                .thenReturn(Optional.of(order("ORD_1", OrderStatus.PENDING, "10")));

        assertThatThrownBy(() -> service.createVnpayUrl(99L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("order not found");
        verifyNoInteractions(paymentRepository, builder, settlementService);
    }

    @ParameterizedTest
    @CsvSource({"0,false", "1,false", "0,true", "1,true"})
    void equalityAndAfterCutoffRejectWithoutExtendingOrWriting(long nanosAfter, boolean sweepingPaused) {
        lenient().when(reservationPolicy.isSweepingPaused()).thenReturn(sweepingPaused);
        Order order = order("ORD_1", OrderStatus.PENDING, "10");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "10");
        eligible(order, payment);
        lenient().when(clock.now()).thenReturn(DEADLINE.plusNanos(nanosAfter));
        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(order.getPaymentExpiresAt()).isEqualTo(DEADLINE);
        verifyNoInteractions(builder);
        noWrites();
    }

    @Test
    void justBeforeCutoffAndRepeatedIssuanceReuseSameSignedDeadline() {
        Order order = order("ORD_1", OrderStatus.PENDING, "150000");
        eligible(order, payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "150000"));
        lenient().when(clock.now()).thenReturn(DEADLINE.minusNanos(1));
        for (int i = 0; i < 2; i++) {
            Map<String, String> fields = query(service.createVnpayUrl(7L, "ORD_1", "1.2.3.4").payUrl());
            assertThat(signature.verifyParams(fields)).isTrue();
            assertThat(fields).containsEntry("vnp_ExpireDate", "20261114000102")
                    .containsEntry("vnp_TxnRef", "PAY_1").containsEntry("vnp_Amount", "15000000");
        }
        assertThat(order.getPaymentExpiresAt()).isEqualTo(DEADLINE);
        noWrites();
    }

    @Test
    void activeNullDeadlineIsQuarantinedWithoutBackfill() {
        Order order = order("ORD_1", OrderStatus.PENDING, "10");
        order.setPaymentExpiresAt(null);
        eligible(order, payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "10"));
        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(order.getPaymentExpiresAt()).isNull();
        verifyNoInteractions(builder);
        noWrites();
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void allPauseCombinationsKeepActivationAndResumeDeadline(boolean active, boolean online, boolean sweep) {
        lenient().when(reservationPolicy.isActive()).thenReturn(active);
        lenient().when(reservationPolicy.isOnlineWorkPaused()).thenReturn(online);
        lenient().when(reservationPolicy.isSweepingPaused()).thenReturn(sweep);
        Order order = order("ORD_1", OrderStatus.PENDING, "10");
        eligible(order, payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "10"));
        if (online) {
            assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                    .isInstanceOf(InvalidOrderStateException.class);
            verifyNoInteractions(builder);
        }
        lenient().when(reservationPolicy.isOnlineWorkPaused()).thenReturn(false);
        Map<String, String> fields = query(service.createVnpayUrl(7L, "ORD_1", "1.2.3.4").payUrl());
        assertThat(signature.verifyParams(fields)).isTrue();
        if (active) assertThat(fields).containsEntry("vnp_ExpireDate", "20261114000102");
        else assertLegacyWindow(fields);
        assertThat(order.getPaymentExpiresAt()).isEqualTo(DEADLINE);
        noWrites();
    }

    @Test
    void onlinePausePrecedesOwnershipAndStateClassification() {
        lenient().when(reservationPolicy.isOnlineWorkPaused()).thenReturn(true);
        for (String orderId : List.of("unknown", "ORD_foreign", "ORD_COD")) {
            assertThatThrownBy(() -> service.createVnpayUrl(99L, orderId, "1.2.3.4"))
                    .isInstanceOf(InvalidOrderStateException.class)
                    .hasMessage("online payment is temporarily unavailable")
                    .hasMessageNotContaining("order not found");
        }
        verifyNoInteractions(orderRepository, paymentRepository, builder, settlementService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inactiveOldOrdersIgnoreNullAndPastProposedDeadlines(boolean proposed) {
        lenient().when(reservationPolicy.isActive()).thenReturn(false);
        Order order = order("ORD_1", OrderStatus.PENDING, "10");
        order.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        Instant stored = proposed ? Instant.parse("2020-01-01T00:15:00Z") : null;
        order.setPaymentExpiresAt(stored);
        eligible(order, payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "10"));
        Map<String, String> fields = query(service.createVnpayUrl(7L, "ORD_1", "1.2.3.4").payUrl());
        assertThat(signature.verifyParams(fields)).isTrue();
        assertLegacyWindow(fields);
        assertThat(order.getPaymentExpiresAt()).isEqualTo(stored);
        noWrites();
    }

    @ParameterizedTest
    @MethodSource("inconsistentReservations")
    void inconsistentPendingReservationsCannotDemandAnotherPayment(boolean active, String evidence) {
        lenient().when(reservationPolicy.isActive()).thenReturn(active);
        Order order = order("ORD_1", OrderStatus.PENDING, "10");
        Payment payment = payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "10");
        switch (evidence) {
            case "released" -> order.setStockReleasedAt(DEADLINE.minusSeconds(60));
            case "reason" -> order.setCancellationReason(com.project.dacn.event.OrderEventReason.PAYMENT_FAILED);
            case "paid" -> payment.setPaidAt(DEADLINE.minusSeconds(60));
            case "reference" -> payment.setTransactionId("known-money");
            case "total" -> order.setTotal(new BigDecimal("11"));
            case "method" -> payment.setMethod(null);
            case "amount" -> payment.setAmount(null);
            case "null-total" -> order.setTotal(null);
            case "items" -> when(orderItemRepository.findStockReleaseTargetsByOrderId("ORD_1")).thenReturn(List.of());
            default -> throw new AssertionError(evidence);
        }
        eligible(order, payment);
        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(InvalidOrderStateException.class).hasMessage("reservation requires review");
        verifyNoInteractions(builder);
        noWrites();
    }

    static Stream<Arguments> inconsistentReservations() {
        return Stream.of(true, false).flatMap(active -> Stream.of("released", "reason", "paid", "reference",
                "total", "method", "amount", "null-total", "items").map(evidence -> Arguments.of(active, evidence)));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "SHIPPING", "COMPLETED", "CANCELLED"})
    void nonPendingOrdersKeepExistingRefusal(OrderStatus status) {
        Order order = order("ORD_1", status, "10");
        eligible(order, payment("PAY_1", order, PaymentMethod.E_WALLET, PaymentStatus.PENDING, "10"));
        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(status.name());
        verifyNoInteractions(builder);
        noWrites();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "REFUNDED", "REFUND_REQUIRED"})
    void nonPendingPaymentsKeepExistingRefusal(PaymentStatus status) {
        Order order = order("ORD_1", OrderStatus.PENDING, "10");
        eligible(order, payment("PAY_1", order, PaymentMethod.E_WALLET, status, "10"));
        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(status.name());
        verifyNoInteractions(builder);
        noWrites();
    }

    @Test
    void missingOrderDoesNotReadPaymentOrRevealOtherState() {
        when(orderRepository.findWithLockByOrderId("unknown")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createVnpayUrl(7L, "unknown", "1.2.3.4"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("order not found: unknown");
        verifyNoInteractions(paymentRepository, builder, settlementService);
    }

    @Test
    void ownedOrderWithoutPaymentKeepsNotFoundResponse() {
        when(orderRepository.findWithLockByOrderId("ORD_1"))
                .thenReturn(Optional.of(order("ORD_1", OrderStatus.PENDING, "10")));
        when(paymentRepository.findWithLockByOrder_OrderId("ORD_1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createVnpayUrl(7L, "ORD_1", "1.2.3.4"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("payment not found: ORD_1");
        verifyNoInteractions(builder);
        noWrites();
    }

    private void eligible(Order order, Payment payment) {
        lenient().when(orderRepository.findWithLockByOrderId("ORD_1")).thenReturn(Optional.of(order));
        lenient().when(paymentRepository.findWithLockByOrder_OrderId("ORD_1")).thenReturn(Optional.of(payment));
    }

    private void noWrites() {
        verify(paymentRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(settlementService);
    }

    private static void assertLegacyWindow(Map<String, String> fields) {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        LocalDateTime created = LocalDateTime.parse(fields.get("vnp_CreateDate"), format);
        assertThat(LocalDateTime.parse(fields.get("vnp_ExpireDate"), format)).isEqualTo(created.plusMinutes(15));
        assertThat(created.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()).isAfter(Instant.now().minusSeconds(60));
    }

    private static Map<String, String> query(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
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

    private static Order order(String orderId, OrderStatus status, String total) {
        return Order.builder()
                .orderId(orderId)
                .customer(Customer.builder().id(7L).customerId("cust").name("C").phone("090").build())
                .status(status)
                .total(new BigDecimal(total))
                .paymentExpiresAt(DEADLINE)
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
