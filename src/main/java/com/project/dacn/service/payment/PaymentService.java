package com.project.dacn.service.payment;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.dto.response.payment.PaymentUrlResponse;
import com.project.dacn.dto.response.payment.PaymentVietQrResponse;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.integration.payment.PaymentSettlementService;
import com.project.dacn.integration.payment.vietqr.VietQrProperties;
import com.project.dacn.integration.payment.vietqr.VietQrUrlBuilder;
import com.project.dacn.integration.payment.vnpay.VnpayUrlBuilder;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final String VNPAY_SUCCESS = "00";

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final VnpayUrlBuilder vnpayUrlBuilder;
    private final VietQrUrlBuilder vietQrUrlBuilder;
    private final VietQrProperties vietQrProperties;
    private final PaymentSettlementService settlementService;
    private final PaymentMapper paymentMapper;

    @Transactional(readOnly = true)
    public PaymentUrlResponse createVnpayUrl(Long customerId, String orderId, String ipAddress) {
        Payment payment = requirePayment(customerId, orderId);
        Order order = payment.getOrder();
        if (payment.getMethod() == PaymentMethod.COD
                || payment.getMethod() == PaymentMethod.VIETQR) {
            throw new IllegalArgumentException(
                    "payment method does not need a VNPay URL: " + payment.getMethod());
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new IllegalArgumentException("payment is not pending: " + payment.getStatus());
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalArgumentException("order is not pending: " + order.getStatus());
        }
        String url = vnpayUrlBuilder.build(payment.getPaymentId(), wholeVnd(payment.getAmount()),
                "Thanh toan don hang " + orderId, ipAddress);
        log.info("Created VNPay URL for order {} payment {}", orderId, payment.getPaymentId());
        return new PaymentUrlResponse(url);
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long customerId, String orderId) {
        return paymentMapper.toPaymentResponse(requirePayment(customerId, orderId));
    }

    @Transactional(readOnly = true)
    public PaymentVietQrResponse createVietQr(Long customerId, String orderId) {
        Payment payment = requirePayment(customerId, orderId);
        if (payment.getMethod() != PaymentMethod.VIETQR) {
            throw new IllegalArgumentException(
                    "payment is not a VIETQR payment: " + payment.getMethod());
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new IllegalArgumentException("payment is not pending: " + payment.getStatus());
        }
        if (payment.getOrder().getStatus() != OrderStatus.PENDING) {
            throw new IllegalArgumentException(
                    "order is not pending: " + payment.getOrder().getStatus());
        }
        String qrUrl = vietQrUrlBuilder.build(orderId, wholeVnd(payment.getAmount()));
        log.info("Created VietQR URL for order {} payment {}", orderId, payment.getPaymentId());
        return new PaymentVietQrResponse(qrUrl,
                vietQrProperties.getBankId(), vietQrProperties.getAccountNo(),
                vietQrProperties.getAccountName(), payment.getAmount(), orderId);
    }

    @Transactional
    public PaymentResponse confirmVietQrPayment(String orderId) {
        Payment payment = paymentRepository.findByOrder_OrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("payment not found: " + orderId));
        if (payment.getMethod() != PaymentMethod.VIETQR) {
            throw new IllegalArgumentException(
                    "payment is not a VIETQR payment: " + payment.getMethod());
        }
        var result = settlementService.settle(orderId, payment.getAmount(), true, null);
        if (result == PaymentSettlementService.Result.NOT_FOUND) {
            throw new ResourceNotFoundException("payment not found: " + orderId);
        }
        if (result == PaymentSettlementService.Result.AMOUNT_MISMATCH) {
            throw new IllegalStateException("VietQR amount mismatch for order " + orderId);
        }
        Payment settled = paymentRepository.findWithOrderByOrder_OrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("payment not found: " + orderId));
        log.info("Confirmed VietQR payment for order {}: {}", orderId, settled.getStatus());
        return paymentMapper.toPaymentResponse(settled);
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
     * Applies a signature-verified VNPay IPN. Settlement is a conditional
     * UPDATE, so duplicate/concurrent IPNs settle at most once.
     */
    @Transactional
    public IpnResult handleIpn(Map<String, String> params) {
        String txnRef = params.get("vnp_TxnRef");
        Payment payment = paymentRepository.findByPaymentId(txnRef).orElse(null);
        if (payment == null) {
            log.warn("VNPay IPN for unknown payment {}", txnRef);
            return IpnResult.ORDER_NOT_FOUND;
        }
        if (!amountMatches(payment.getAmount(), params.get("vnp_Amount"))) {
            log.warn("VNPay IPN amount mismatch for payment {}: sent {}", txnRef, params.get("vnp_Amount"));
            return IpnResult.AMOUNT_MISMATCH;
        }
        String orderId = payment.getOrder().getOrderId();
        boolean success = VNPAY_SUCCESS.equals(params.get("vnp_ResponseCode"));
        if (!success) {
            int updated = paymentRepository.settle(txnRef, PaymentStatus.PENDING,
                    PaymentStatus.FAILED, null, Instant.now());
            log.info("VNPay payment {} failed (response {})", txnRef, params.get("vnp_ResponseCode"));
            return updated == 1 ? IpnResult.FAILED : alreadySettled(txnRef, IpnResult.FAILED);
        }
        int updated = paymentRepository.settle(txnRef, PaymentStatus.PENDING, PaymentStatus.CONFIRMED,
                params.get("vnp_TransactionNo"), Instant.now());
        if (updated == 0) {
            return alreadySettled(txnRef, IpnResult.CONFIRMED);
        }
        orderRepository.updateStatus(orderId, OrderStatus.PENDING, OrderStatus.CONFIRMED);
        log.info("VNPay payment {} confirmed for order {}", txnRef, orderId);
        return IpnResult.CONFIRMED;
    }

    private IpnResult alreadySettled(String txnRef, IpnResult outcome) {
        PaymentStatus status = paymentRepository.findByPaymentId(txnRef)
                .map(Payment::getStatus)
                .orElse(null);
        if (status == PaymentStatus.CONFIRMED) {
            return IpnResult.ALREADY_CONFIRMED;
        }
        if (status == PaymentStatus.FAILED) {
            return IpnResult.ALREADY_FAILED;
        }
        log.warn("VNPay payment {} is {} after losing settle race, acknowledging", txnRef, status);
        return outcome;
    }

    private static boolean amountMatches(BigDecimal amount, String vnpAmount) {
        if (vnpAmount == null) {
            return false;
        }
        try {
            return amount.movePointRight(2).toBigIntegerExact()
                    .equals(new BigDecimal(vnpAmount).toBigIntegerExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return false;
        }
    }

    private static long wholeVnd(BigDecimal amount) {
        try {
            return amount.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount must be a whole VND value: " + amount);
        }
    }

    public enum IpnResult {
        CONFIRMED, ALREADY_CONFIRMED, FAILED, ALREADY_FAILED, ORDER_NOT_FOUND, AMOUNT_MISMATCH
    }
}
