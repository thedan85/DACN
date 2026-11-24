package com.project.dacn.messaging;

import com.project.dacn.event.OrderEvent;

public interface OrderEventPublisher {

    void publish(OrderEvent event) throws EventPublishException;
}
