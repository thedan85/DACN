package com.project.dacn.service.order;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.messaging.outbox.OrderEventOutbox;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderLifecycleServiceTest {

    private final OrderEventOutbox outbox = mock(OrderEventOutbox.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final OrderLifecycleService service = new OrderLifecycleService(
            mock(OrderRepository.class),
            mock(PaymentRepository.class),
            mock(OrderItemRepository.class),
            mock(VariantRepository.class),
            mock(OrderResponseAssembler.class),
            outbox,
            clock);

    @Test
    void paymentFromAnotherOrderFailsBeforeAppend() {
        Order order = order(1L, "10.00");
        Payment payment = payment(2L, order(2L, "10.00"), "10.00");

        assertThatThrownBy(() -> service.recordPlaced(order, payment, "TEST"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payment does not belong to the order being recorded");
        verifyNoInteractions(outbox, clock);
    }

    @Test
    void paymentAmountMismatchFailsBeforeAppend() {
        Order order = order(1L, "10.00");
        Payment payment = payment(3L, order, "9.00");

        assertThatThrownBy(() -> service.recordPlaced(order, payment, "TEST"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payment amount must match the order total");
        verifyNoInteractions(outbox, clock);
    }

    private static Order order(long id, String total) {
        return Order.builder()
                .id(id)
                .orderId("ORD_" + id)
                .status(OrderStatus.PENDING)
                .total(new BigDecimal(total))
                .build();
    }

    private static Payment payment(long id, Order order, String amount) {
        return Payment.builder()
                .id(id)
                .order(order)
                .method(PaymentMethod.COD)
                .status(PaymentStatus.PENDING)
                .amount(new BigDecimal(amount))
                .build();
    }
}
