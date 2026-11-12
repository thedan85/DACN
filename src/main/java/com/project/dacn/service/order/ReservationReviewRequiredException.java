package com.project.dacn.service.order;

/** An unresolved reservation needs owned reconciliation, not an automatic callback repair. */
public class ReservationReviewRequiredException extends RuntimeException {
    public ReservationReviewRequiredException(String orderId) {
        super("reservation requires review: " + orderId);
    }
}
