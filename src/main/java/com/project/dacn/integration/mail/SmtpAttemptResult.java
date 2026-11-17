package com.project.dacn.integration.mail;

/**
 * Transport-level outcome of one SMTP attempt. Deliberately coarse: it records
 * only what the transport can prove, so a later ledger layer maps it to SENT,
 * retryable failure or UNKNOWN without guessing from an exception message.
 */
public record SmtpAttemptResult(Outcome outcome, String failureCode) {

    public enum Outcome {
        /** Final SMTP acceptance was observed (the 2xx reply to the message). */
        ACCEPTED,
        /** Failure before acceptance was proven; no message can have been accepted. */
        PRE_ACCEPTANCE_FAILURE,
        /** Deadline or connection loss left possible acceptance unobserved. */
        UNKNOWN
    }

    public static SmtpAttemptResult accepted() {
        return new SmtpAttemptResult(Outcome.ACCEPTED, null);
    }

    public static SmtpAttemptResult preAcceptanceFailure(String code) {
        return new SmtpAttemptResult(Outcome.PRE_ACCEPTANCE_FAILURE, safe(code));
    }

    public static SmtpAttemptResult unknown(String code) {
        return new SmtpAttemptResult(Outcome.UNKNOWN, safe(code));
    }

    private static String safe(String code) {
        if (code == null || code.isBlank()) {
            return "UNSPECIFIED";
        }
        String cleaned = code.trim().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
