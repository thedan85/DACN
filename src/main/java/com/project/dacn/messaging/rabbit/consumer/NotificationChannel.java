package com.project.dacn.messaging.rabbit.consumer;

import com.project.dacn.messaging.rabbit.RabbitTopology;

import java.time.Duration;
import java.util.Map;

public enum NotificationChannel {

    INAPP(RabbitTopology.INAPP_QUEUE),
    EMAIL(RabbitTopology.EMAIL_QUEUE);

    private static final Map<Duration, String> RETRY_SUFFIXES = Map.of(
            Duration.ofSeconds(10), "10s",
            Duration.ofSeconds(30), "30s",
            Duration.ofSeconds(120), "120s");

    private final String mainQueue;

    NotificationChannel(String mainQueue) {
        this.mainQueue = mainQueue;
    }

    public String mainQueue() {
        return mainQueue;
    }

    public String dlq() {
        return mainQueue + ".dlq";
    }

    public String retryQueue(Duration delay) {
        String suffix = RETRY_SUFFIXES.get(delay);
        if (suffix == null) {
            throw new IllegalArgumentException("unsupported retry delay");
        }
        return mainQueue + ".retry." + suffix;
    }
}
