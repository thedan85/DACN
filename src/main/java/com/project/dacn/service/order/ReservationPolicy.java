package com.project.dacn.service.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Operator deployment configuration, not a mutable control or a durable latch.
 * After the approved activation, every instance/restart/rollback must retain true.
 * Pauses stop admission only; they never turn effective deadlines off.
 */
@Component
public final class ReservationPolicy {

    private final boolean active;
    private final boolean onlineWorkPaused;
    private final boolean sweepingPaused;

    public ReservationPolicy(
            @Value("${app.inventory.reservation-policy-enabled:false}") boolean active,
            @Value("${app.inventory.online-work-paused:false}") boolean onlineWorkPaused,
            @Value("${app.inventory.expiry-sweeping-paused:false}") boolean sweepingPaused) {
        this.active = active;
        this.onlineWorkPaused = onlineWorkPaused;
        this.sweepingPaused = sweepingPaused;
    }

    /** Sole gate for deadline interpretation, independent of both admission pauses. */
    public boolean isActive() {
        return active;
    }

    /** Reject new online checkout/URL work only, not COD or fully verified callbacks. */
    public boolean isOnlineWorkPaused() {
        return onlineWorkPaused;
    }

    /** Stop new scheduled expiry claims only, not a callback's locked expiry decision. */
    public boolean isSweepingPaused() {
        return sweepingPaused;
    }
}
