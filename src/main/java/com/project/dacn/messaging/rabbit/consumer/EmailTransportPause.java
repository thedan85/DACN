package com.project.dacn.messaging.rabbit.consumer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime gate for unsafe email sending. Never affects the in-app channel. */
@Component
@ConditionalOnProperty(name = "app.notifications.delivery-enabled", havingValue = "true")
public class EmailTransportPause {

    private final AtomicBoolean paused = new AtomicBoolean(false);

    public boolean isPaused() {
        return paused.get();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }
}
