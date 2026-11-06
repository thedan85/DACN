package com.project.dacn.event;

import java.util.Locale;

public enum OrderEventType {
    ORDER_PLACED, ORDER_CONFIRMED, ORDER_REJECTED, ORDER_EXPIRED,
    PAYMENT_FAILED, REFUND_REQUIRED, ORDER_COMPLETED, ORDER_SHIPPED, ORDER_REFUNDED;

    public String routingKey() {
        return "order.lifecycle." + name().toLowerCase(Locale.ROOT);
    }
}
