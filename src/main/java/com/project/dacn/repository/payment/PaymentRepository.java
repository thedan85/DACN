package com.project.dacn.repository.payment;

import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.entity.payment.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPaymentId(String paymentId);

    Optional<Payment> findByOrder_OrderId(String orderId);

    Optional<Payment> findByTransactionId(String transactionId);

    /**
     * Settles a payment only while it is still in {@code current}. Two IPNs
     * arriving at once both issue this UPDATE; the loser blocks on the row
     * lock, re-checks the predicate and updates 0 rows.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Payment p set p.status = :next, p.transactionId = :transactionId, "
            + "p.paidAt = :paidAt where p.paymentId = :paymentId and p.status = :current")
    int settle(@Param("paymentId") String paymentId,
               @Param("current") PaymentStatus current,
               @Param("next") PaymentStatus next,
               @Param("transactionId") String transactionId,
               @Param("paidAt") Instant paidAt);
}
