package com.project.dacn.messaging.rabbit;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

@RequiredArgsConstructor
public class RabbitDeliveryStartup implements ApplicationRunner {

    private final AmqpAdmin admin;
    private final RabbitTopologyVerifier verifier;
    private final RabbitListenerEndpointRegistry listeners;
    private volatile boolean ready;

    @Override
    public void run(ApplicationArguments args) {
        ready = false;
        admin.initialize();
        verifier.run(args);
        listeners.start();
        ready = true;
    }

    public boolean isReady() {
        return ready;
    }
}
