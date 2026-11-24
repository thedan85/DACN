package com.project.dacn.service.engagement;

import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.project.dacn.common.constant.PaymentMethod.COD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gated real-provider proof. Opt in with DACN_EMAIL_PROOF_GMAIL=true and supply the
 * recipient plus Gmail credentials in the process environment only. Nothing here
 * is committed with a real address or secret, and no production bypass is added.
 */
@EnabledIfEnvironmentVariable(named = "DACN_EMAIL_PROOF_GMAIL", matches = "true")
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DACN_EMAIL_PROOF_RECIPIENT", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("external-rabbit-dacn-topology")
class RealGmailDeliveryProofTest {

    @Test
    void oneRealGmailMessage_reachesSentThroughFullPath() throws Exception {
        String recipient = System.getenv("DACN_EMAIL_PROOF_RECIPIENT");
        Task16JourneyHarness h = null;
        try {
            h = Task16JourneyHarness.create();
            h.setCustomerEmail(recipient);

            OrderEvent event = h.placeOrderWithOutbox("ORD_gmail_proof", COD);
            h.relay.poll();
            assertThat(h.count(RabbitTopology.EMAIL_QUEUE)).as("relay published one email").isEqualTo(1);
            assertThat(h.deliverEmail()).as("consumer consumed the event").isTrue();

            String state = h.strings(
                    "select state from email_deliveries where event_id = '" + event.eventId() + "'")
                    .getFirst();
            assertThat(state).isEqualTo("SENT");
            assertThat(h.scalarLong(
                    "select count(*) from email_deliveries where event_id = '" + event.eventId() + "'"))
                    .as("no duplicate ledger row").isEqualTo(1);
            assertThat(h.count(RabbitTopology.EMAIL_QUEUE)).as("no redelivery").isZero();
            assertThat(h.acceptanceEpochMillis)
                    .as("acceptance inferred from sendMessage return and nonzero timestamp")
                    .isGreaterThan(0L);

            String completedAt = h.strings(
                    "select completed_at::text from email_deliveries where event_id = '"
                            + event.eventId() + "'").getFirst();
            System.out.println("GMAIL_PROOF acceptanceInferred=yes wire250Captured=no"
                    + " ledger=SENT attempts=1 duplicates=0"
                    + " acceptedAtEpochMillis=" + h.acceptanceEpochMillis
                    + " ledgerCompletedAt=" + completedAt);
        } catch (Exception failure) {
            System.out.println("GMAIL_PROOF_FAILED " + describe(failure));
            // Rethrow only a bounded, sanitized failure so no address or secret
            // can appear in a Maven stack trace.
            throw new AssertionError("real Gmail proof failed; see redacted GMAIL_PROOF_FAILED line");
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    private static String describe(Exception failure) {
        int smtpCode = -1;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.eclipse.angus.mail.smtp.SMTPSendFailedException rejected) {
                smtpCode = rejected.getReturnCode();
                break;
            }
        }
        String raw = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        String safe = raw.replaceAll("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+", "<redacted>")
                .replaceAll("(?i)(password|passwd|pass)\\s*[=:]\\s*\\S+", "$1=<redacted>")
                .replaceAll("[\\r\\n]+", " ");
        return "exception=" + failure.getClass().getSimpleName()
                + " smtpCode=" + smtpCode + " message=" + safe;
    }
}
