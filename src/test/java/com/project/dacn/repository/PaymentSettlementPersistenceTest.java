package com.project.dacn.repository;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.messaging.outbox.OrderEventOutbox;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.service.order.OrderLifecycleService;
import com.project.dacn.service.order.OrderResponseAssembler;
import com.project.dacn.service.order.ReservationPolicy;
import jakarta.persistence.EntityManager;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import com.project.dacn.repository.catalog.ProductRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Guards the conditional UPDATE that makes concurrent VNPay IPNs settle once:
 * the second settle hits zero rows because the status is no longer PENDING.
 */
@DataJpaTest(properties = "app.inventory.reservation-policy-enabled=true")
@ActiveProfiles("test")
@Import({OrderLifecycleService.class, PaymentSettlementService.class, ReservationPolicy.class})
class PaymentSettlementPersistenceTest {

    @Autowired
    RoleRepository roleRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CustomerRepository customerRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired PaymentSettlementService settlement;
    @Autowired VariantRepository variants;
    @Autowired OrderItemRepository items;
    @Autowired EntityManager entityManager;
    @MockitoBean DatabaseClock clock;
    @MockitoBean OrderEventOutbox outbox;
    @MockitoBean OrderResponseAssembler responseAssembler;

    Order order;
    Payment payment;
    Variant variant;
    private static final Instant NOW = Instant.parse("2026-11-13T01:02:03Z");

    @BeforeEach
    void seed() {
        Role role = roleRepository.save(Role.builder().code("paysettle").name("R").build());
        User user = userRepository.save(User.builder()
                .email("paysettle@x.com").passwordHash("h").role(role).build());
        Customer customer = customerRepository.save(Customer.builder()
                .user(user).name("C").phone("090").build());
        when(clock.now()).thenReturn(NOW);
        Product product = productRepository.save(Product.builder().name("P").description("d").build());
        variant = variants.save(Variant.builder().product(product).sku("PAY-STOCK").price(new BigDecimal("50000"))
                .stock(7).build());
        order = orderRepository.save(Order.builder()
                .customer(customer).total(new BigDecimal("150000.00"))
                .address("addr").recipientName("C").phone("090").paymentExpiresAt(NOW.plusSeconds(60)).build());
        items.save(OrderItem.builder().order(order).variant(variant).quantity(3).unitPrice(new BigDecimal("50000")).build());
        payment = paymentRepository.save(Payment.builder()
                .order(order).method(PaymentMethod.E_WALLET)
                .amount(new BigDecimal("150000.00")).build());
    }

    @Test
    void settleWinsOnlyOnce() {
        int first = paymentRepository.settle(payment.getPaymentId(), PaymentStatus.PENDING,
                PaymentStatus.CONFIRMED, "TX1", Instant.now());
        int second = paymentRepository.settle(payment.getPaymentId(), PaymentStatus.PENDING,
                PaymentStatus.CONFIRMED, "TX1", Instant.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        Payment fresh = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(fresh.getTransactionId()).isEqualTo("TX1");
        assertThat(fresh.getPaidAt()).isNotNull();
    }

    @Test
    void failedIpnCannotDowngradeConfirmedPayment() {
        paymentRepository.settle(payment.getPaymentId(), PaymentStatus.PENDING,
                PaymentStatus.CONFIRMED, "TX1", Instant.now());

        int downgrade = paymentRepository.settle(payment.getPaymentId(), PaymentStatus.PENDING,
                PaymentStatus.FAILED, null, Instant.now());

        assertThat(downgrade).isZero();
        Payment fresh = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void orderStatusUpdateIsConditional() {
        int first = orderRepository.updateStatus(order.getOrderId(),
                OrderStatus.PENDING, OrderStatus.CONFIRMED);
        int second = orderRepository.updateStatus(order.getOrderId(),
                OrderStatus.PENDING, OrderStatus.CANCELLED);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(orderRepository.findByOrderId(order.getOrderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void sharedSettlementPersistsConfirmationWithoutSecondDeduction() {
        assertThat(settlement.settle(order.getOrderId(), payment.getAmount(), true, "LOCKED-TX"))
                .isEqualTo(PaymentSettlementService.Result.SETTLED);
        entityManager.flush(); entityManager.clear();
        Payment fresh = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(fresh.getPaidAt()).isEqualTo(NOW);
        assertThat(fresh.getOrder().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(variants.findById(variant.getId()).orElseThrow().getStock()).isEqualTo(7);
    }

    @Test
    void sharedFailurePersistsReleaseAndNoUnpaidPaidAt() {
        assertThat(settlement.settle(order.getOrderId(), payment.getAmount(), false, null))
                .isEqualTo(PaymentSettlementService.Result.SETTLED);
        entityManager.flush(); entityManager.clear();
        Payment fresh = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(fresh.getPaidAt()).isNull();
        assertThat(fresh.getTransactionId()).isNull();
        assertThat(fresh.getOrder().getStockReleasedAt()).isEqualTo(NOW);
        assertThat(variants.findById(variant.getId()).orElseThrow().getStock()).isEqualTo(10);
    }

    @Test
    void sharedEqualityPersistsBothSequencesAndProtectsReceivedEvidenceOnReplay() {
        order.setPaymentExpiresAt(NOW);
        assertThat(settlement.settle(order.getOrderId(), payment.getAmount(), true, "LATE-TX"))
                .isEqualTo(PaymentSettlementService.Result.REFUND_REQUIRED);
        entityManager.flush(); entityManager.clear();
        assertThat(settlement.settle(order.getOrderId(), payment.getAmount(), true, "OVERWRITE"))
                .isEqualTo(PaymentSettlementService.Result.ALREADY_SETTLED);
        assertThat(settlement.settle(order.getOrderId(), payment.getAmount(), false, null))
                .isEqualTo(PaymentSettlementService.Result.ALREADY_SETTLED);
        entityManager.flush(); entityManager.clear();
        Payment fresh = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        assertThat(fresh.getTransactionId()).isEqualTo("LATE-TX");
        assertThat(fresh.getPaidAt()).isEqualTo(NOW);
        assertThat(fresh.getOrder().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(fresh.getOrder().getEventSequence()).isEqualTo(2);
        assertThat(variants.findById(variant.getId()).orElseThrow().getStock()).isEqualTo(10);
    }

    @Test
    void paymentReferenceProjectionResolvesIdentityWithoutManagingBusinessEntities() {
        entityManager.flush(); entityManager.clear();
        assertThat(paymentRepository.findOrderIdByPaymentId(payment.getPaymentId())).contains(order.getOrderId());
        assertThat(entityManager.contains(order)).isFalse();
        assertThat(entityManager.contains(payment)).isFalse();
    }
}
