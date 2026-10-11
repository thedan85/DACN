package com.project.dacn.repository.order;

import com.project.dacn.entity.order.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPaymentId(String paymentId);

    Optional<Payment> findByOrder_OrderId(String orderId);

    Optional<Payment> findByTransactionId(String transactionId);
}
