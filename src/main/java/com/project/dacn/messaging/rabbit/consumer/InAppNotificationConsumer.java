package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import com.project.dacn.messaging.outbox.OrderEventCodec;
import com.project.dacn.messaging.outbox.OrderEventCodecException;
import com.project.dacn.service.engagement.NotificationFanoutService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "app.notifications.delivery-enabled", havingValue = "true")
public class InAppNotificationConsumer {

    public static final String LISTENER_ID = "order-notifications-inapp";

    private final OrderEventCodec codec;
    private final NotificationConsumerSupport support;
    private final NotificationFanoutService fanout;

    public InAppNotificationConsumer(OrderEventCodec codec,
                                     NotificationConsumerSupport support,
                                     NotificationFanoutService fanout) {
        this.codec = codec;
        this.support = support;
        this.fanout = fanout;
    }

    @RabbitListener(id = LISTENER_ID, queues = RabbitTopology.INAPP_QUEUE)
    public void receive(Message message, com.rabbitmq.client.Channel channel) throws IOException {
        OrderEvent event;
        try {
            event = codec.decode(message.getBody());
        } catch (OrderEventCodecException failure) {
            // Raw body and message identity survive the permanent quarantine.
            support.handlePermanentFailure(NotificationChannel.INAPP, message, channel,
                    null, "EVENT_INVALID");
            return;
        }

        // Age is rejected before any recipient resolution or fan-out work.
        if (support.rejectIfOutsideWindow(NotificationChannel.INAPP, message, channel, event)) {
            return;
        }

        try {
            // The transactional handler commits before this returns.
            fanout.handle(event);
        } catch (RuntimeException failure) {
            // Confirmed retry/DLQ transfer owns the ACK; a failed transfer propagates
            // so the original message stays unacknowledged.
            support.handleTransientFailure(NotificationChannel.INAPP, message, channel,
                    event, failure);
            return;
        }

        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}
