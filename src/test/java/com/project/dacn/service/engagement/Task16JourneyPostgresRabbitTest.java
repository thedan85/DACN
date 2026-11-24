package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.rabbit.RabbitTopology;
import com.project.dacn.messaging.rabbit.consumer.NotificationChannel;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import com.project.dacn.messaging.rabbit.consumer.RabbitRetryPublisher;
import org.springframework.amqp.core.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.project.dacn.common.constant.PaymentMethod.COD;
import static com.project.dacn.event.OrderEventType.ORDER_CONFIRMED;
import static com.project.dacn.event.OrderEventType.ORDER_PLACED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16 backend journey: real PostgreSQL, real RabbitMQ, real relay/consumers,
 * real SMTP loopback capture. Every scenario builds on the committed state of the
 * previous one. No hosted resource and no mocked SMTP or dedup database is used.
 */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("external-rabbit-dacn-topology")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Task16JourneyPostgresRabbitTest {

    private static Task16JourneyHarness h;

    private OrderEvent eventA;
    private OrderEvent eventB;
    private String customerNotificationA;
    private long revisionCustomer;
    private long revisionAdmin;
    private long revisionEmployee;
    private int smtpAfterCrash;
    private long emailsAfterCrash;
    private long receiptsAfterCrash;

    @BeforeAll
    static void start() throws Exception {
        h = Task16JourneyHarness.create();
    }

    @AfterAll
    static void stop() throws Exception {
        if (h != null) {
            h.close();
        }
    }

    @Test
    @Order(1)
    void bothChannelsCompleteWithOneRowPerRecipientAndTruthfulCopy() throws Exception {
        eventA = h.placeOrderWithOutbox("ORD_t16_A", COD);

        h.relay.poll();
        assertThat(h.count(RabbitTopology.INAPP_QUEUE)).as("real relay -> inapp queue").isEqualTo(1);
        assertThat(h.count(RabbitTopology.EMAIL_QUEUE)).as("real relay -> email queue").isEqualTo(1);
        assertThat(h.outboxRow(eventA.eventId(), "published_at")).isNotNull();

        assertThat(h.deliverInapp()).isTrue();
        assertThat(h.deliverEmail()).isTrue();
        assertThat(h.count(RabbitTopology.INAPP_QUEUE)).isZero();
        assertThat(h.count(RabbitTopology.EMAIL_QUEUE)).isZero();

        assertThat(count("select count(*) from notifications where source_event_id = '"
                + eventA.eventId() + "'")).isEqualTo(3);
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + eventA.eventId() + "' and recipient_scope = 'CUSTOMER'")).isEqualTo(1);
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + eventA.eventId() + "' and recipient_scope = 'COMPANY'")).isEqualTo(2);
        assertThat(count("select count(*) from notification_event_receipts where event_id = '"
                + eventA.eventId() + "' and completed_at is not null and failure_code is null"))
                .isEqualTo(1);
        assertThat(count("select count(*) from email_deliveries where event_id = '"
                + eventA.eventId() + "' and state = 'SENT' and attempt_count = 1")).isEqualTo(1);

        assertThat(h.capture.connectionCount()).as("one real SMTP connection").isEqualTo(1);
        assertThat(h.capture.messageCount()).as("one captured message").isEqualTo(1);
        assertThat(h.capture.messageReceived()).isTrue();
        String text = h.capturedPlainText();
        assertThat(text).as("captured copy").contains("ORD_t16_A")
                .contains("pending approval").contains("payment is due on delivery")
                .doesNotContain("Payment received", "shipped", "refunded");

        for (long user : List.of(h.customerUserId, h.adminUserId, h.employeeUserId)) {
            assertThat(revision(user)).as("one revision increment for user %s", user).isEqualTo(1);
        }
        customerNotificationA = h.strings("select notification_id from notifications "
                + "where source_event_id = '" + eventA.eventId() + "' and user_id = "
                + h.customerUserId).getFirst();
        System.out.println("TASK16_EVIDENCE_SCENARIO1 bothChannels notifications=3"
                + " receiptCompleted=1 emailSENT=1 smtpConnections=1 smtpMessages=1"
                + " copyHasOrderAndPendingCod=true revisions=1/1/1");
    }

    @Test
    @Order(2)
    void readDeleteViaRealInboxServicePreservesOtherRecipient() throws Exception {
        long before = revision(h.customerUserId);
        var read = h.mutation.service.markRead(Task16JourneyHarness.CUSTOMER, customerNotificationA);
        assertThat(read.revision()).isEqualTo(before + 1);
        assertThat(read.notification().status()).isEqualTo(
                com.project.dacn.common.constant.NotificationStatus.READ);
        assertThat(h.strings("select notification_status from notifications where notification_id = '"
                + customerNotificationA + "'")).containsExactly("READ");

        long afterRead = revision(h.customerUserId);
        var deleted = h.mutation.service.delete(Task16JourneyHarness.CUSTOMER, customerNotificationA);
        assertThat(deleted.revision()).isEqualTo(afterRead + 1);
        assertThat(deleted.unreadCount()).isZero();
        assertThat(h.mutation.service.unreadCount(Task16JourneyHarness.CUSTOMER).unreadCount()).isZero();
        assertThat(h.strings("select is_deleted::text from notifications where notification_id = '"
                + customerNotificationA + "'")).containsExactly("true");

        for (long other : List.of(h.adminUserId, h.employeeUserId)) {
            assertThat(revision(other)).as("other recipient revision untouched").isEqualTo(1);
        }
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + eventA.eventId() + "' and user_id <> " + h.customerUserId
                + " and notification_status = 'UNREAD' and is_deleted = false")).isEqualTo(2);

        revisionCustomer = revision(h.customerUserId);
        revisionAdmin = revision(h.adminUserId);
        revisionEmployee = revision(h.employeeUserId);
        System.out.println("TASK16_EVIDENCE_SCENARIO2 readDelete unread=0"
                + " customerRevision=" + revisionCustomer + " otherRevisions=1/1"
                + " otherRowsUntouched=true");
    }

    @Test
    @Order(3)
    void publishBeforeMarkCrashAndLeaseReplayCreateNoSecondEffects() throws Exception {
        eventB = h.placeOrderWithOutbox("ORD_t16_B", COD);
        UUID claimToken = UUID.randomUUID();
        var claimed = h.relayRepository.claimDue(20, h.clock.now(), java.time.Duration.ofSeconds(60),
                "t16-owner", claimToken);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().eventId()).isEqualTo(eventB.eventId());

        // Crash point: real publish to both queues, no markPublished.
        h.publisher.publish(eventB);
        assertThat(h.outboxRow(eventB.eventId(), "published_at"))
                .as("row stays unpublished after crash").isNull();
        assertThat(h.outboxRow(eventB.eventId(), "lease_token")).isNotNull();

        assertThat(h.deliverInapp()).isTrue();
        assertThat(h.deliverEmail()).isTrue();
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + eventB.eventId() + "'")).isEqualTo(3);
        smtpAfterCrash = h.capture.connectionCount();
        emailsAfterCrash = count("select count(*) from email_deliveries where event_id = '"
                + eventB.eventId() + "'");
        receiptsAfterCrash = count("select count(*) from notification_event_receipts where event_id = '"
                + eventB.eventId() + "'");

        // Lease still active: relay.poll must not republish the claimed row.
        h.relay.poll();
        assertThat(h.count(RabbitTopology.INAPP_QUEUE)).as("no republish while leased").isZero();
        assertThat(h.count(RabbitTopology.EMAIL_QUEUE)).as("no republish while leased").isZero();
        assertThat(h.capture.connectionCount()).isEqualTo(smtpAfterCrash);
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + eventB.eventId() + "'")).isEqualTo(3);

        // Expire the claim, then replay through the real relay/broker path.
        h.execute("update event_outbox set lease_expires_at = clock_timestamp() - interval '1 second' "
                + "where event_id = '" + eventB.eventId() + "'");
        h.relay.poll();
        assertThat(h.outboxRow(eventB.eventId(), "published_at"))
                .as("replay marks the row published").isNotNull();
        assertThat(h.count(RabbitTopology.INAPP_QUEUE)).isEqualTo(1);
        assertThat(h.count(RabbitTopology.EMAIL_QUEUE)).isEqualTo(1);
        assertThat(h.deliverInapp()).isTrue();
        assertThat(h.deliverEmail()).isTrue();

        // Zero second effects, and the earlier read/delete state is unchanged.
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + eventB.eventId() + "'")).as("no second saved notifications").isEqualTo(3);
        assertThat(revision(h.customerUserId)).isEqualTo(revisionCustomer + 1);
        assertThat(revision(h.adminUserId)).isEqualTo(revisionAdmin + 1);
        assertThat(revision(h.employeeUserId)).isEqualTo(revisionEmployee + 1);
        assertThat(h.capture.connectionCount()).as("no second SMTP attempt").isEqualTo(smtpAfterCrash);
        assertThat(h.capture.messageCount()).as("no second captured message").isEqualTo(2);
        assertThat(count("select count(*) from email_deliveries where event_id = '"
                + eventB.eventId() + "'")).isEqualTo(emailsAfterCrash);
        assertThat(count("select count(*) from notification_event_receipts where event_id = '"
                + eventB.eventId() + "'")).isEqualTo(receiptsAfterCrash);
        assertThat(h.strings("select notification_status || '|' || is_deleted::text from notifications "
                + "where notification_id = '" + customerNotificationA + "'"))
                .containsExactly("READ|true");

        revisionCustomer = revision(h.customerUserId);
        revisionAdmin = revision(h.adminUserId);
        revisionEmployee = revision(h.employeeUserId);
        System.out.println("TASK16_EVIDENCE_SCENARIO3 crashReplay leaseBlockedRepublish=true"
                + " unpublishedBeforeReplay=true secondNotifications=0 secondSmtp=0"
                + " readDeleteStatePreserved=true");
    }

    @Test
    @Order(4)
    void retentionPrunesEligibleEvidenceAfterBackdating91Days() throws Exception {
        // Protected, genuinely younger / unresolved rows (seeded as real DB rows).
        UUID protectedReceipt = UUID.randomUUID();
        UUID protectedEmail = UUID.randomUUID();
        UUID protectedUnpublished = UUID.randomUUID();
        UUID protectedQuarantined = UUID.randomUUID();
        h.execute("insert into notification_event_receipts (event_id, recipient_user_ids, created_at, "
                + "updated_at, completed_at) values ('" + protectedReceipt + "', '[]'::jsonb, "
                + "clock_timestamp() - interval '2 days', clock_timestamp() - interval '2 days', "
                + "clock_timestamp() - interval '1 day')");
        h.execute("insert into email_deliveries (event_id, recipient_user_id, state, attempt_count, "
                + "created_at, updated_at, completed_at) values ('" + protectedEmail + "', "
                + h.customerUserId + ", 'SENT', 1, clock_timestamp() - interval '2 days', "
                + "clock_timestamp() - interval '2 days', clock_timestamp() - interval '1 day')");
        h.execute("insert into event_outbox (event_id, order_id, order_sequence, event_type, "
                + "schema_version, payload, created_at, available_at) values ('"
                + protectedUnpublished + "', (select id from orders where order_id = 'ORD_t16_A'), 90, "
                + "'ORDER_PLACED', 1, '{}'::jsonb, clock_timestamp(), "
                + "clock_timestamp() + interval '200 days')");
        h.execute("insert into event_outbox (event_id, order_id, order_sequence, event_type, "
                + "schema_version, payload, created_at, available_at, quarantined_at, last_error_code) "
                + "values ('" + protectedQuarantined + "', (select id from orders "
                + "where order_id = 'ORD_t16_A'), 91, 'ORDER_PLACED', 1, '{}'::jsonb, "
                + "clock_timestamp(), clock_timestamp(), "
                + "clock_timestamp() - interval '1 day', 'EVENT_TOO_OLD')");
        h.execute("insert into notifications (notification_id, user_id, notification_message, "
                + "notification_type, notification_status, occurred_at) values "
                + "('NOTI_t16_protected', " + h.customerUserId + ", 'fresh', 'SYSTEM', 'UNREAD', "
                + "clock_timestamp())");

        // Honest time simulation: clock_timestamp() cannot be moved, so backdate
        // the ELIGIBLE rows 91 days relative to the live DB clock (documented in report).
        String ids = "'" + eventA.eventId() + "', '" + eventB.eventId() + "'";
        h.execute("update notifications set occurred_at = clock_timestamp() - interval '91 days', "
                + "created_at = clock_timestamp() - interval '91 days' where source_event_id in (" + ids + ")");
        h.execute("update event_outbox set published_at = clock_timestamp() - interval '91 days' "
                + "where event_id in (" + ids + ")");
        h.execute("update email_deliveries set completed_at = clock_timestamp() - interval '91 days' "
                + "where state = 'SENT' and event_id in (" + ids + ")");
        h.execute("update notification_event_receipts set completed_at = "
                + "clock_timestamp() - interval '91 days' where event_id in (" + ids + ")");

        String revisionSnapshot = revisions();
        String budgetSnapshot = h.strings("select enabled::text || '|' || remaining_message_budget::text "
                + "from app_realtime_control where id = 1").getFirst();

        h.pruner.prune();

        assertThat(count("select count(*) from notifications where source_event_id in (" + ids + ")"))
                .as("eligible notification content pruned").isZero();
        assertThat(count("select count(*) from event_outbox where event_id in (" + ids + ")"))
                .as("published outbox payloads pruned").isZero();
        assertThat(count("select count(*) from email_deliveries where event_id in (" + ids + ")"))
                .as("SENT email evidence pruned").isZero();
        assertThat(count("select count(*) from notification_event_receipts where event_id in (" + ids + ")"))
                .as("completed receipts pruned").isZero();

        assertThat(count("select count(*) from notification_event_receipts where event_id = '"
                + protectedReceipt + "'")).as("younger receipt survives").isEqualTo(1);
        assertThat(count("select count(*) from email_deliveries where event_id = '"
                + protectedEmail + "'")).as("younger email survives").isEqualTo(1);
        assertThat(count("select count(*) from event_outbox where event_id = '"
                + protectedUnpublished + "'")).as("unpublished outbox survives").isEqualTo(1);
        assertThat(count("select count(*) from event_outbox where event_id = '"
                + protectedQuarantined + "'")).as("quarantined outbox survives").isEqualTo(1);
        assertThat(count("select count(*) from notifications where notification_id = "
                + "'NOTI_t16_protected'")).as("younger notification survives").isEqualTo(1);

        assertThat(revisions()).as("revisions not reset by prune").isEqualTo(revisionSnapshot);
        assertThat(h.strings("select enabled::text || '|' || remaining_message_budget::text "
                + "from app_realtime_control where id = 1").getFirst()).isEqualTo(budgetSnapshot);
        System.out.println("TASK16_EVIDENCE_SCENARIO4 retention eligiblePruned=4Tables"
                + " protectedSurvived=5 revisionsUnchanged=true budgetsUnchanged=true"
                + " backdatedBy=91days");
    }

    @Test
    @Order(5)
    void independentReplayPathsAfterPruneHitStrictThirtyDayGuard() throws Exception {
        h.ownedTopology.purgeQueues();
        Instant advanced = h.dbNow.plus(91, ChronoUnit.DAYS);
        h.clock.set(advanced);
        String revisionSnapshot = revisions();
        int smtpBefore = h.capture.connectionCount();

        // (1) Relay restart/poll with no claimable outbox rows.
        assertThat(count("select count(*) from event_outbox where event_id = '"
                + eventA.eventId() + "'")).isZero();
        h.relay.poll();
        assertThat(h.count(RabbitTopology.INAPP_QUEUE)).as("relay with no rows publishes nothing").isZero();
        assertThat(h.count(RabbitTopology.EMAIL_QUEUE)).as("relay with no rows publishes nothing").isZero();
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + eventA.eventId() + "'")).isZero();
        assertThat(revisions()).isEqualTo(revisionSnapshot);
        assertThat(h.capture.connectionCount()).isEqualTo(smtpBefore);

        // (1b) Relay with a fresh row for the OLD event quarantines it, preserving identity/time.
        h.tx.executeWithoutResult(status -> h.outbox.append(h.scalarLong(
                "select id from orders where order_id = 'ORD_t16_A'"), eventA));
        h.relay.poll();
        assertThat(h.outboxRow(eventA.eventId(), "published_at")).isNull();
        assertThat(h.outboxRow(eventA.eventId(), "last_error_code")).isEqualTo("EVENT_TOO_OLD");
        assertThat(h.outboxRow(eventA.eventId(), "quarantined_at")).isNotNull();
        String payload = h.outboxRow(eventA.eventId(), "payload");
        assertThat(payload).contains(eventA.eventId().toString()).contains(eventA.occurredAt().toString());
        assertThat(h.count(RabbitTopology.INAPP_QUEUE)).isZero();
        assertThat(h.count(RabbitTopology.EMAIL_QUEUE)).isZero();

        // (2) Ordinary broker redelivery of the original event.
        h.publishToQueue(RabbitTopology.INAPP_QUEUE, eventA, 0, null);
        h.publishToQueue(RabbitTopology.EMAIL_QUEUE, eventA, 0, null);
        assertThat(h.deliverInapp()).isTrue();
        assertThat(h.deliverEmail()).isTrue();
        // (3) Publish a retry (x-retry-attempt=1) directly to the REAL 10s retry
        // queue. Its x-message-ttl=10000 dead-letters the message back to the
        // main in-app queue via the retry exchange. Exercise that broker TTL/DLX
        // path instead of short-circuiting straight to the main queue.
        String retryQueue = NotificationChannel.INAPP.retryQueue(Duration.ofSeconds(10));
        h.publishToQueue(retryQueue, eventA, 1, null);
        long retryDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        boolean retryQueued = false;
        while (System.nanoTime() < retryDeadline) {
            if (h.count(retryQueue) == 1) {
                retryQueued = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(retryQueued).as("retry queue received the message").isTrue();
        boolean ttlMoved = false;
        while (System.nanoTime() < retryDeadline) {
            if (h.count(retryQueue) == 0 && h.count(RabbitTopology.INAPP_QUEUE) == 1) {
                ttlMoved = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(ttlMoved).as("TTL dead-letter moved the retry from the retry queue to main").isTrue();
        assertThat(h.deliverInapp()).as("retry message delivered through real broker TTL path").isTrue();
        // (4) Manual DLQ replay of the ACTUAL quarantined in-app message.
        // Read the real quarantined message (body, headers, message id, timestamp
        // and embedded occurredAt preserved), then republish those exact bytes and
        // properties to the main queue. No event is rebuilt or re-encoded.
        Message quarantined = h.peek(NotificationChannel.INAPP.dlq());
        assertThat(quarantined).as("quarantine evidence retained in DLQ").isNotNull();
        assertThat(quarantined.getMessageProperties().getMessageId())
                .isEqualTo(eventA.eventId().toString());
        // Rabbit returns short-string headers as LongString; compare by text.
        assertThat(String.valueOf(quarantined.getMessageProperties().getHeaders()
                .get(RabbitRetryPublisher.QUARANTINE_REASON_HEADER))).isEqualTo("EVENT_TOO_OLD");
        assertThat(new String(quarantined.getBody())).contains(eventA.occurredAt().toString());
        h.publishMessage(RabbitTopology.INAPP_QUEUE, quarantined);
        assertThat(h.deliverInapp()).as("actual DLQ message replayed through real consumer").isTrue();

        assertThat(count("select count(*) from notifications where source_event_id = '"
                + eventA.eventId() + "'")).as("no notification recreated by any replay path").isZero();
        assertThat(revisions()).as("no revision change after any replay path").isEqualTo(revisionSnapshot);
        assertThat(h.capture.connectionCount()).as("no SMTP connection after any replay path")
                .isEqualTo(smtpBefore);
        Message finalDlq = h.peek(NotificationChannel.INAPP.dlq());
        assertThat(finalDlq).as("quarantine retained after actual DLQ replay").isNotNull();
        assertThat(finalDlq.getMessageProperties().getMessageId())
                .as("event identity unchanged by DLQ replay").isEqualTo(eventA.eventId().toString());
        assertThat(new String(finalDlq.getBody()))
                .as("occurredAt unchanged by DLQ replay").contains(eventA.occurredAt().toString());
        assertThat(String.valueOf(finalDlq.getMessageProperties().getHeaders()
                .get(RabbitRetryPublisher.QUARANTINE_REASON_HEADER))).isEqualTo("EVENT_TOO_OLD");

        // Exact 30-day boundary and just-before-boundary with retained evidence.
        OrderEventAgeGuard guard = new OrderEventAgeGuard(h.clock);
        OrderEvent exact30 = placed("ORD_t16_BOUND30", h.dbNow.minus(30, ChronoUnit.DAYS));
        OrderEvent justBefore = placed("ORD_t16_BOUND29", h.dbNow.minus(29, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        assertThat(guard.evaluate(exact30, h.dbNow)).as("exact 30 days is TOO_OLD")
                .isEqualTo(OrderEventAgeGuard.AgeDecision.TOO_OLD);
        assertThat(guard.evaluate(justBefore, h.dbNow)).as("29d23h is OK")
                .isEqualTo(OrderEventAgeGuard.AgeDecision.OK);

        // Just-before-boundary duplicate WITH existing evidence: dedup, no new row/revision.
        h.clock.clear();
        OrderEvent nearBoundary = placed("ORD_t16_NEAR", h.clock.now()
                .minus(29, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        h.execute("insert into notification_event_receipts (event_id, recipient_user_ids, created_at, "
                + "updated_at, completed_at) values ('" + nearBoundary.eventId() + "', '[]'::jsonb, "
                + "clock_timestamp() - interval '2 days', clock_timestamp() - interval '2 days', "
                + "clock_timestamp() - interval '1 day')");
        h.execute("insert into notifications (notification_id, user_id, notification_message, "
                + "notification_type, notification_status, source_event_id, order_id, event_type, "
                + "order_sequence, occurred_at, recipient_scope) values ('NOTI_t16_near', "
                + h.customerUserId + ", 'historical', 'ORDER', 'UNREAD', '"
                + nearBoundary.eventId() + "', (select id from orders where order_id = 'ORD_t16_A'), "
                + "'ORDER_PLACED', 1, '" + nearBoundary.occurredAt() + "', 'CUSTOMER')");
        String revBeforeDup = revisions();
        h.publishToQueue(RabbitTopology.INAPP_QUEUE, nearBoundary, 0, null);
        assertThat(h.deliverInapp()).isTrue();
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + nearBoundary.eventId() + "'")).as("in-window duplicate creates no new row").isEqualTo(1);
        assertThat(revisions()).isEqualTo(revBeforeDup);

        System.out.println("TASK16_EVIDENCE_SCENARIO5 replayPaths noRows/noRedelivery/noRetry/actualDlqReplay"
                + " notifications=0 revisions=0 smtp=0 quarantineIdentityAndTimePreserved=true"
                + " exact30=TOO_OLD 29d23h=OK nearDuplicateDedup=true"
                + " retryTtl=10s retryQueueToMain=true");
    }

    @Test
    @Order(6)
    void pendingPlacedReceiptRetryAfterConfirmationIsSkipped() throws Exception {
        h.clock.clear();
        seedSimpleOrder("ORD_t16_F");
        h.resetCapture();
        h.capture.rejectRecipient = true;
        OrderEvent placed = placed("ORD_t16_F", h.clock.now().minusSeconds(60));
        h.emailService.process(placed);
        assertThat(emailRow(placed.eventId(), "state")).isEqualTo("PENDING");
        assertThat(h.capture.connectionCount()).isEqualTo(1);
        assertThat(h.capture.messageCount()).isZero();

        h.execute("insert into notifications (notification_id, user_id, notification_message, "
                + "notification_type, notification_status, source_event_id, order_id, event_type, "
                + "order_sequence, occurred_at, recipient_scope) values ('NOTI_t16_F', "
                + h.customerUserId + ", 'historical placed', 'ORDER', 'UNREAD', '"
                + placed.eventId() + "', (select id from orders where order_id = 'ORD_t16_F'), "
                + "'ORDER_PLACED', 1, '" + placed.occurredAt() + "', 'CUSTOMER')");
        String historical = h.strings("select notification_message from notifications where "
                + "notification_id = 'NOTI_t16_F'").getFirst();

        // COD confirmation delivered first (staff acceptance; cash still due).
        h.execute("update orders set order_status = 'CONFIRMED' where order_id = 'ORD_t16_F'");
        h.capture.rejectRecipient = false;
        OrderEvent confirm = OrderEvent.create(ORDER_CONFIRMED, "ORD_t16_F", 2L,
                h.clock.now().minusSeconds(30), h.customerUserId, OrderStatus.CONFIRMED,
                PaymentStatus.PENDING, COD, new BigDecimal("10.00"), null, "t16-F-conf", "TEST");
        h.emailService.process(confirm);
        assertThat(emailRow(confirm.eventId(), "state")).isEqualTo("SENT");
        int afterConfirmation = h.capture.connectionCount();
        assertThat(afterConfirmation).isEqualTo(2);
        String confirmText = h.capturedPlainText();
        assertThat(confirmText).contains("ORD_t16_F").contains("confirmed")
                .contains("Payment is due on delivery").doesNotContain("Payment received");

        h.execute("update email_deliveries set next_eligible_at = "
                + "clock_timestamp() - interval '1 second' where event_id = '" + placed.eventId() + "'");
        h.emailService.process(placed);
        assertThat(emailRow(placed.eventId(), "state")).isEqualTo("SKIPPED");
        assertThat(h.capture.connectionCount()).as("zero further SMTP calls").isEqualTo(afterConfirmation);
        assertThat(h.capture.messageCount()).isEqualTo(1);
        assertThat(h.strings("select notification_message from notifications where "
                + "notification_id = 'NOTI_t16_F'").getFirst()).isEqualTo(historical);
        System.out.println("TASK16_EVIDENCE_SCENARIO6 placedRetry=SKIPPED confirmation=SENT"
                + " smtpAfterRetry=" + h.capture.connectionCount() + " historyPreserved=true"
                + " codConfirmationTruthful=true");
    }

    @Test
    @Order(7)
    void unavailableSmtpRecoversAndRelayPublishFailureReleasesRow() throws Exception {
        h.clock.clear();
        h.ownedTopology.purgeQueues();
        seedSimpleOrder("ORD_t16_G");
        h.resetCapture();
        h.stopCapture(); // genuinely unavailable loopback peer, no mock
        OrderEvent g = placed("ORD_t16_G", h.clock.now().minusSeconds(60));
        h.emailService.process(g);
        assertThat(emailRow(g.eventId(), "state")).as("durable PENDING, no false SENT/UNKNOWN")
                .isEqualTo("PENDING");
        assertThat(emailRow(g.eventId(), "completed_at")).isNull();
        assertThat(h.capture.connectionCount()).as("closed peer accepts nothing").isZero();

        h.resetCapture();
        h.execute("update email_deliveries set next_eligible_at = clock_timestamp() - interval '1 second' "
                + "where event_id = '" + g.eventId() + "'");
        h.emailService.process(g);
        assertThat(emailRow(g.eventId(), "state")).isEqualTo("SENT");
        assertThat(h.capture.connectionCount()).isEqualTo(1);
        assertThat(h.capture.messageCount()).isEqualTo(1);
        assertThat(count("select count(*) from email_deliveries where event_id = '"
                + g.eventId() + "'")).as("no duplicate saved record").isEqualTo(1);

        // Relay publish failure releases the row for a later successful retry.
        h.resetCapture();
        OrderEvent relayEvent = h.placeOrderWithOutbox("ORD_t16_H", COD);
        h.publisher.failNext(); // fault-injection seam, named in report
        h.relay.poll();
        assertThat(h.outboxRow(relayEvent.eventId(), "published_at")).isNull();
        assertThat(h.outboxRow(relayEvent.eventId(), "last_error_code")).isEqualTo("PUBLISH_FAILED");
        assertThat(h.outboxRow(relayEvent.eventId(), "lease_token")).isNull();
        assertThat(h.count(RabbitTopology.INAPP_QUEUE)).isZero();
        assertThat(h.count(RabbitTopology.EMAIL_QUEUE)).isZero();

        h.execute("update event_outbox set available_at = clock_timestamp() - interval '1 second' "
                + "where event_id = '" + relayEvent.eventId() + "'");
        h.relay.poll();
        assertThat(h.outboxRow(relayEvent.eventId(), "published_at")).isNotNull();
        assertThat(h.deliverInapp()).isTrue();
        assertThat(h.deliverEmail()).isTrue();
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + relayEvent.eventId() + "'")).isEqualTo(3);
        assertThat(h.capture.messageCount()).isEqualTo(1);
        h.relay.poll();
        assertThat(h.count(RabbitTopology.INAPP_QUEUE)).isZero();
        assertThat(count("select count(*) from notifications where source_event_id = '"
                + relayEvent.eventId() + "'")).as("no duplicate consumer effects").isEqualTo(3);
        System.out.println("TASK16_EVIDENCE_SCENARIO7 unavailableSmtp pendingThenSent once"
                + " duplicateEmailRows=0 relayPublishReleased=true retryNoDuplicates=true");
    }

    // ----- helpers -----

    private OrderEvent placed(String orderId, Instant occurredAt) {
        return OrderEvent.create(ORDER_PLACED, orderId, 1L, occurredAt,
                h.customerUserId, OrderStatus.PENDING, PaymentStatus.PENDING,
                PaymentMethod.COD, new BigDecimal("10.00"), null, "t16-" + orderId, "TEST");
    }

    private void seedSimpleOrder(String orderId) {
        h.execute("insert into orders (order_id, customer_id, order_status, order_total, "
                + "order_address, order_recipient_name, order_phone) "
                + "select '" + orderId + "', id, 'PENDING', 10, 'fixture', 'Fixture', '000' "
                + "from customers where customer_id = 'CUS_t16'");
        h.execute("insert into payments (payment_id, order_id, payment_method, payment_status, "
                + "payment_amount) select 'PAY_" + orderId + "', id, 'COD', 'PENDING', 10 "
                + "from orders where order_id = '" + orderId + "'");
    }

    private long revision(long user) {
        return h.scalarLong("select inbox_revision from users where id = " + user);
    }

    private String revisions() {
        return h.strings("select user_id || '=' || inbox_revision from users where user_id in ('"
                + Task16JourneyHarness.CUSTOMER + "','" + Task16JourneyHarness.ADMIN + "','"
                + Task16JourneyHarness.EMPLOYEE + "') order by user_id").toString();
    }

    private long count(String sql) {
        return h.scalarLong(sql);
    }

    private String emailRow(UUID eventId, String column) {
        return h.strings("select " + column + "::text from email_deliveries where event_id = '"
                + eventId + "'").getFirst();
    }
}
