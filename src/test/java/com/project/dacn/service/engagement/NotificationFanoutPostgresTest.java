package com.project.dacn.service.engagement;

import com.project.dacn.common.time.DatabaseClock;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.messaging.rabbit.consumer.OrderEventAgeGuard;
import com.project.dacn.support.FanoutTestHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.project.dacn.common.constant.PaymentMethod.COD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class NotificationFanoutPostgresTest {

    private static FanoutTestHarness h;

    @BeforeAll
    static void setUp() throws SQLException {
        h = FanoutTestHarness.create();
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (h != null) {
            h.close();
        }
    }

    @BeforeEach
    void reset() throws SQLException {
        h.execute("drop trigger if exists t3_fail_notify_trg on notifications");
        h.execute("drop function if exists t3_fail_notify()");
        h.execute("delete from notifications where source_event_id is not null");
        h.execute("delete from notification_event_receipts");
        h.execute("delete from email_deliveries");
        h.execute("delete from employees where employee_id = 'EMP_t3_new'");
        h.execute("delete from users where user_id = 'USR_t3_new_emp'");
        h.execute("update users set user_status = 'ACTIVE' where user_id in "
                + "('USR_t3_cust', 'USR_t3_admin', 'USR_t3_emp')");
        h.execute("update users set inbox_revision = 0 where user_id in "
                + "('USR_t3_cust', 'USR_t3_admin', 'USR_t3_emp', 'USR_t3_new_emp')");
    }

    @Test
    void concurrentIdenticalEventsCreateOneReceiptAndOneRowPerRecipient() throws Exception {
        UUID eventId = UUID.randomUUID();
        OrderEvent event = h.freshEvent(eventId, COD);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                h.handle(event);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(h.scalarLong("select count(*) from notification_event_receipts "
                + "where event_id = '" + eventId + "'")).isEqualTo(1);
        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + eventId + "'")).isEqualTo(3);
        assertThat(h.longs("select user_id from notifications "
                + "where source_event_id = '" + eventId + "' order by user_id asc"))
                .containsExactlyElementsOf(expectedRecipients());
        assertThat(h.scalarLong("select count(*) from email_deliveries "
                + "where event_id = '" + eventId + "'")).isZero();

        for (long userId : expectedRecipients()) {
            assertThat(h.scalarLong("select inbox_revision from users where id = " + userId))
                    .as("revision for user %s", userId).isEqualTo(1);
        }
        assertThat(h.strings("select recipient_user_ids::text from notification_event_receipts "
                + "where event_id = '" + eventId + "'"))
                .containsExactly(frozenJson(expectedRecipients()));
        assertThat(h.strings("select completed_at is not null from notification_event_receipts "
                + "where event_id = '" + eventId + "'")).containsExactly("t");
    }

    @Test
    void midFanoutFailureRollsBackAllRecipientRowsRevisionsAndReceipt() throws Exception {
        UUID eventId = UUID.randomUUID();
        long failUser = expectedRecipients().getLast();
        h.execute("create or replace function t3_fail_notify() returns trigger as $$ "
                + "begin if new.user_id = " + failUser + " then "
                + "raise exception 'injected t3 fan-out failure'; end if; return new; end; $$ "
                + "language plpgsql");
        h.execute("create trigger t3_fail_notify_trg before insert on notifications "
                + "for each row execute function t3_fail_notify()");

        OrderEvent event = h.freshEvent(eventId, COD);
        assertThatThrownBy(() -> h.handle(event))
                .hasStackTraceContaining("injected t3 fan-out failure");

        assertThat(h.scalarLong("select count(*) from notification_event_receipts "
                + "where event_id = '" + eventId + "'")).isZero();
        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + eventId + "'")).isZero();
        for (long userId : expectedRecipients()) {
            assertThat(h.scalarLong("select inbox_revision from users where id = " + userId))
                    .as("rolled-back revision for user %s", userId).isZero();
        }

        h.execute("drop trigger t3_fail_notify_trg on notifications");
        h.execute("drop function t3_fail_notify()");
        h.handle(event);
        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + eventId + "'")).isEqualTo(3);
        for (long userId : expectedRecipients()) {
            assertThat(h.scalarLong("select inbox_revision from users where id = " + userId))
                    .isEqualTo(1);
        }
    }

    @Test
    void recipientsLockedInAscendingInternalUserIdOrder() throws Exception {
        UUID eventId = UUID.randomUUID();
        OrderEvent event = h.freshEvent(eventId, COD);
        List<Long> recipients = expectedRecipients();
        long lowest = recipients.getFirst();
        long highest = recipients.getLast();

        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> holder = pool.submit(() -> holdRowLock(highest, holderReady, releaseHolder));
        assertThat(holderReady.await(15, TimeUnit.SECONDS)).isTrue();

        Future<?> fanout = pool.submit(() -> {
            h.handle(event);
            return null;
        });

        try {
            boolean lowestLockedByFanout = waitForNowaitLockFailure(lowest);
            assertThat(fanout.isDone()).as("fan-out must still block on the highest-ID user").isFalse();
            assertThat(lowestLockedByFanout)
                    .as("fan-out must acquire the lowest-ID user lock before the highest-ID lock")
                    .isTrue();
        } finally {
            releaseHolder.countDown();
            fanout.get(30, TimeUnit.SECONDS);
            holder.get(30, TimeUnit.SECONDS);
            pool.shutdownNow();
        }

        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + eventId + "'")).isEqualTo(3);
    }

    @Test
    void ageRecheckedAfterLockWaitQuarantinesWithoutEffects() throws Exception {
        UUID eventId = UUID.randomUUID();
        List<Long> recipients = expectedRecipients();
        long highest = recipients.getLast();
        long lowest = recipients.getFirst();
        OrderEvent event = h.freshEvent(eventId, COD);

        MutableClock clock = new MutableClock(h.jdbc, h.dbNow);
        OrderEventAgeGuard guard = new OrderEventAgeGuard(clock);
        NotificationFanoutService service = new NotificationFanoutService(
                h.repository, h.queries, clock, guard,
                new NotificationMessageRenderer(), new OrderNotificationPolicy());
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(h.dataSource));

        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> holder = pool.submit(() -> holdRowLock(highest, holderReady, releaseHolder));
        assertThat(holderReady.await(15, TimeUnit.SECONDS)).isTrue();

        Future<?> fanout = pool.submit(() -> {
            tx.executeWithoutResult(status -> service.handle(event));
            return null;
        });

        try {
            assertThat(waitForNowaitLockFailure(lowest)).isTrue();
            clock.now = h.dbNow.plus(31, ChronoUnit.DAYS);
        } finally {
            releaseHolder.countDown();
            fanout.get(30, TimeUnit.SECONDS);
            holder.get(30, TimeUnit.SECONDS);
            pool.shutdownNow();
        }

        assertThat(h.strings("select failure_code from notification_event_receipts "
                + "where event_id = '" + eventId + "'")).containsExactly("EVENT_TOO_OLD");
        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + eventId + "'")).isZero();
        for (long userId : recipients) {
            assertThat(h.scalarLong("select inbox_revision from users where id = " + userId)).isZero();
        }
    }

    @Test
    void frozenMembershipReplayAfterHireAndRemovalAddsNothing() throws Exception {
        UUID eventId = UUID.randomUUID();
        OrderEvent event = h.freshEvent(eventId, COD);
        h.handle(event);

        List<Long> frozen = expectedRecipients();
        assertThat(h.strings("select recipient_user_ids::text from notification_event_receipts "
                + "where event_id = '" + eventId + "'")).containsExactly(frozenJson(frozen));

        long newEmployeeId = hireEmployee();
        h.execute("update users set user_status = 'LOCKED' where user_id = 'USR_t3_emp'");

        h.handle(event);

        assertThat(h.scalarLong("select count(*) from notification_event_receipts "
                + "where event_id = '" + eventId + "'")).isEqualTo(1);
        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + eventId + "'")).isEqualTo(3);
        assertThat(h.scalarLong("select count(*) from notifications where user_id = " + newEmployeeId))
                .isZero();
        assertThat(h.scalarLong("select inbox_revision from users where id = " + newEmployeeId))
                .isZero();
        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + eventId + "' and user_id = " + h.employeeId))
                .isEqualTo(1);
        assertThat(h.strings("select notification_message from notifications "
                + "where source_event_id = '" + eventId + "' and user_id = " + h.employeeId))
                .hasSize(1);
        assertThat(h.strings("select recipient_user_ids::text from notification_event_receipts "
                + "where event_id = '" + eventId + "'")).containsExactly(frozenJson(frozen));
    }

    @Test
    void staleAndFutureEventsQuarantinedWithoutAudience() throws Exception {
        UUID staleId = UUID.randomUUID();
        h.handle(h.event(staleId, h.customerId, h.dbNow.minus(31, ChronoUnit.DAYS), COD, h.orderId));
        assertThat(h.strings("select failure_code from notification_event_receipts "
                + "where event_id = '" + staleId + "'")).containsExactly("EVENT_TOO_OLD");
        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + staleId + "'")).isZero();

        UUID futureId = UUID.randomUUID();
        h.handle(h.event(futureId, h.customerId, h.dbNow.plus(1, ChronoUnit.HOURS), COD, h.orderId));
        assertThat(h.strings("select failure_code from notification_event_receipts "
                + "where event_id = '" + futureId + "'")).containsExactly("EVENT_FUTURE");
        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + futureId + "'")).isZero();

        for (long userId : expectedRecipients()) {
            assertThat(h.scalarLong("select inbox_revision from users where id = " + userId)).isZero();
        }
    }

    @Test
    void claimedButIncompleteReceiptQuarantinesWithoutNewAudience() throws Exception {
        UUID eventId = UUID.randomUUID();
        h.execute("insert into notification_event_receipts (event_id, recipient_user_ids, claimed_at) "
                + "values ('" + eventId + "'::uuid, '[]'::jsonb, clock_timestamp())");

        h.handle(h.freshEvent(eventId, COD));

        assertThat(h.strings("select failure_code from notification_event_receipts "
                + "where event_id = '" + eventId + "'"))
                .containsExactly("MISSING_COMPLETED_EVIDENCE");
        assertThat(h.strings("select recipient_user_ids::text from notification_event_receipts "
                + "where event_id = '" + eventId + "'")).containsExactly("[]");
        assertThat(h.scalarLong("select count(*) from notifications "
                + "where source_event_id = '" + eventId + "'")).isZero();
        for (long userId : expectedRecipients()) {
            assertThat(h.scalarLong("select inbox_revision from users where id = " + userId)).isZero();
        }
    }

    private long hireEmployee() throws SQLException {
        h.execute("insert into users (user_id, user_email, user_password_hash, user_status, role_id) "
                + "select 'USR_t3_new_emp', 't3_new_emp@example.invalid', 'unused', 'ACTIVE', id "
                + "from roles where role_code = 'employee'");
        h.execute("insert into employees (employee_id, user_id, employee_name, employee_status) "
                + "select 'EMP_t3_new', id, 'Fixture', 'ACTIVE' "
                + "from users where user_id = 'USR_t3_new_emp'");
        return h.scalarLong("select id from users where user_id = 'USR_t3_new_emp'");
    }

    private List<Long> expectedRecipients() {
        return Stream.of(h.customerId, h.adminId, h.employeeId)
                .sorted(Comparator.naturalOrder()).toList();
    }

    private static String frozenJson(List<Long> userIds) {
        return "[" + String.join(", ", userIds.stream().map(String::valueOf).toList()) + "]";
    }

    private void holdRowLock(long userId, CountDownLatch ready, CountDownLatch release) {
        try (Connection connection = h.connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery("select id from users where id = " + userId + " for update");
            }
            ready.countDown();
            release.await();
            connection.rollback();
        } catch (Exception exception) {
            ready.countDown();
            throw new RuntimeException(exception);
        }
    }

    /** True when another transaction already holds a row-level lock on the user. */
    private boolean waitForNowaitLockFailure(long userId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Connection connection = h.connect()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.executeQuery("select id from users where id = " + userId
                            + " for update nowait");
                    connection.rollback();
                } catch (SQLException exception) {
                    if ("55P03".equals(exception.getSQLState())) {
                        return true;
                    }
                    throw exception;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static final class MutableClock extends DatabaseClock {
        private volatile Instant now;

        private MutableClock(org.springframework.jdbc.core.JdbcTemplate jdbc, Instant now) {
            super(jdbc);
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }
    }
}
