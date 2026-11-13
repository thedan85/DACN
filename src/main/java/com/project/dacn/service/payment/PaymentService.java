package com.project.dacn.service.payment;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.exception.InvalidOrderStateException;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.dto.response.payment.PaymentUrlResponse;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.payment.vnpay.VnpayUrlBuilder;
import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import com.project.dacn.service.order.ReservationPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final String VNPAY_SUCCESS = "00";

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final VnpayUrlBuilder vnpayUrlBuilder;
    private final PaymentMapper paymentMapper;
    private final PaymentSettlementService settlementService;
    private final ReservationPolicy reservationPolicy;
    private final DatabaseClock clock;
    private final OrderItemRepository orderItemRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private volatile Boolean transactionLockBounds;

    @Transactional
    public PaymentUrlResponse createVnpayUrl(Long customerId, String orderId, String ipAddress) {
        if (reservationPolicy.isOnlineWorkPaused()) {
            throw new InvalidOrderStateException("online payment is temporarily unavailable");
        }
        boundLockWait();
        Order order = orderRepository.findWithLockByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("order not found: " + orderId));
        // A caller or inverse association may already have managed an older snapshot.
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        if (customerId == null || order.getCustomer() == null
                || !customerId.equals(order.getCustomer().getId())) {
            throw new ResourceNotFoundException("order not found: " + orderId);
        }
        Payment payment = paymentRepository.findWithLockByOrder_OrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("payment not found: " + orderId));
        entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
        if (payment.getMethod() == PaymentMethod.COD) {
            throw new IllegalArgumentException("COD order does not need a payment URL");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new IllegalArgumentException("payment is not pending: " + payment.getStatus());
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalArgumentException("order is not pending: " + order.getStatus());
        }
        if (payment.getMethod() == null || payment.getPaidAt() != null || payment.getTransactionId() != null
                || order.getStockReleasedAt() != null || order.getCancellationReason() != null
                || payment.getAmount() == null || order.getTotal() == null
                || payment.getAmount().compareTo(order.getTotal()) != 0
                || orderItemRepository.findStockReleaseTargetsByOrderId(orderId).isEmpty()) {
            throw new InvalidOrderStateException("reservation requires review");
        }
        boolean active = reservationPolicy.isActive();
        if (active) {
            if (order.getPaymentExpiresAt() == null) {
                throw new InvalidOrderStateException("reservation requires review");
            }
            if (!clock.now().isBefore(order.getPaymentExpiresAt())) {
                throw new InvalidOrderStateException("payment reservation has expired");
            }
        }
        long amount = wholeVnd(payment.getAmount());
        String info = "Thanh toan don hang " + orderId;
        String url = active
                ? vnpayUrlBuilder.build(payment.getPaymentId(), amount, info, ipAddress, order.getPaymentExpiresAt())
                : vnpayUrlBuilder.build(payment.getPaymentId(), amount, info, ipAddress);
        log.info("Created VNPay URL for order {} payment {}", orderId, payment.getPaymentId());
        return new PaymentUrlResponse(url);
    }

    private void boundLockWait() {
        if (transactionLockBounds == null) {
            transactionLockBounds = "PostgreSQL".equals(entityManager.unwrap(Session.class)
                    .doReturningWork(connection -> connection.getMetaData().getDatabaseProductName()));
        }
        if (transactionLockBounds) {
            // PostgreSQL ignores positive JPA lock hints; keep this bound transaction-local.
            entityManager.createNativeQuery("set local lock_timeout = '2s'").executeUpdate();
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long customerId, String orderId) {
        return paymentMapper.toPaymentResponse(requirePayment(customerId, orderId));
    }

    /**
     * One query on the happy path. Only an empty result pays for the
     * follow-up order lookup that distinguishes "no such order" from
     * "order exists but has no payment".
     */
    private Payment requirePayment(Long customerId, String orderId) {
        return paymentRepository.findWithOrderByOrderIdAndCustomerId(orderId, customerId)
                .orElseGet(() -> {
                    if (orderRepository.findByOrderIdAndCustomer_Id(orderId, customerId).isEmpty()) {
                        throw new ResourceNotFoundException("order not found: " + orderId);
                    }
                    throw new ResourceNotFoundException("payment not found: " + orderId);
                });
    }

    /** Resolves the order behind a VNPay transaction reference for the return redirect. */
    @Transactional(readOnly = true)
    public String orderIdForPayment(String paymentId) {
        return paymentRepository.findByPaymentId(paymentId)
                .map(payment -> payment.getOrder().getOrderId())
                .orElse(null);
    }

    /**
     * Applies a signature-verified VNPay IPN through the order-locked lifecycle.
     * The payment business reference resolves identity only, never settlement state.
     */
    @Transactional
    public IpnResult handleIpn(Map<String, String> params) {
        String txnRef = params.get("vnp_TxnRef");
        BigDecimal amount;
        try {
            amount = new BigDecimal(new BigDecimal(params.get("vnp_Amount")).toBigIntegerExact()).movePointLeft(2);
        } catch (ArithmeticException | NumberFormatException | NullPointerException invalid) {
            return IpnResult.AMOUNT_MISMATCH;
        }
        String orderId = paymentRepository.findOrderIdByPaymentId(txnRef).orElse(null);
        if (orderId == null) {
            log.warn("VNPay IPN for unknown payment {}", txnRef);
            return IpnResult.ORDER_NOT_FOUND;
        }
        boolean success = VNPAY_SUCCESS.equals(params.get("vnp_ResponseCode"));
        return switch (settlementService.settle(orderId, amount, success, params.get("vnp_TransactionNo"))) {
            case SETTLED -> success ? IpnResult.CONFIRMED : IpnResult.FAILED;
            case ALREADY_SETTLED -> IpnResult.ALREADY_SETTLED;
            case REFUND_REQUIRED -> IpnResult.REFUND_REQUIRED;
            case NOT_FOUND -> IpnResult.ORDER_NOT_FOUND;
            case AMOUNT_MISMATCH -> IpnResult.AMOUNT_MISMATCH;
        };
    }

    private static long wholeVnd(BigDecimal amount) {
        try {
            return amount.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount must be a whole VND value: " + amount);
        }
    }

    public enum IpnResult {
        CONFIRMED, ALREADY_CONFIRMED, FAILED, ALREADY_FAILED, ORDER_NOT_FOUND, AMOUNT_MISMATCH,
        ALREADY_SETTLED, REFUND_REQUIRED
    }
}
