package com.project.dacn.service.order;

/** A known candidate-local fault; the enclosing lifecycle transaction must roll back. */
final class ExpiryCandidateFailure extends RuntimeException {
    private final String orderId;

    ExpiryCandidateFailure(String orderId, RuntimeException cause) {
        super("Expiry candidate failed", cause);
        this.orderId = orderId;
    }

    String orderId() {
        return orderId;
    }
}
