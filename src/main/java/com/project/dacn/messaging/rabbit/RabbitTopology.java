package com.project.dacn.messaging.rabbit;

public final class RabbitTopology {

    public static final String EXCHANGE = "dacn.order.events";
    public static final String RETRY_EXCHANGE = "dacn.order.events.retry";
    public static final String DLX = "dacn.order.events.dlx";
    public static final String INAPP_QUEUE = "dacn.notifications.inapp";
    public static final String EMAIL_QUEUE = "dacn.notifications.email";
    public static final String ROUTING_PATTERN = "order.lifecycle.#";

    private RabbitTopology() {
    }
}
