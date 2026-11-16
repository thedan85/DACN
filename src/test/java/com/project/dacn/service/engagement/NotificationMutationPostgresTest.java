package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.response.engagement.NotificationCountResponse;
import com.project.dacn.dto.response.engagement.NotificationReadResponse;
import com.project.dacn.support.InboxTestHarness;
import com.project.dacn.support.MutationTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.access.AccessDeniedException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.project.dacn.support.InboxTestHarness.ADMIN;
import static com.project.dacn.support.InboxTestHarness.CUSTOMER;
import static com.project.dacn.support.InboxTestHarness.OTHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** Real-PostgreSQL coverage for owned READ/DELETE mutations and revision serialization. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class NotificationMutationPostgresTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    private static InboxTestHarness h;
    private static MutationTestSupport mutation;

    @BeforeAll
    static void startDatabase() throws SQLException {
        h = InboxTestHarness.create();
        mutation = MutationTestSupport.create(h.dataSource);
    }

    @AfterAll
    static void stopDatabase() throws SQLException {
        if (mutation != null) {
            mutation.close();
        }
        if (h != null) {
            h.close();
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        h.reset();
        setRevision(CUSTOMER, 10);
    }

    @Test
    void markReadOnceAdvancesRevisionAndReturnsAuthoritativeState() throws SQLException {
        String id = insertUnread(CUSTOMER, "CUSTOMER", "read-once");

        NotificationReadResponse response = mutation.service.markRead(CUSTOMER, id);

        assertThat(response.revision()).isEqualTo(11);
        assertThat(response.unreadCount()).isZero();
        assertThat(response.notification().notificationId()).isEqualTo(id);
        assertThat(response.notification().status()).isEqualTo(NotificationStatus.READ);
        assertThat(revision(CUSTOMER)).isEqualTo(11);
        assertThat(visibleCount(id)).isEqualTo(1);
        assertThat(h.scalarLongDirect("select count(*) from notifications where notification_id = '"
                + id + "' and notification_status = 'READ' and is_deleted = false")).isEqualTo(1);
    }

    @Test
    void secondMarkReadIsNoopWithoutAnotherIncrement() throws SQLException {
        String id = insertUnread(CUSTOMER, "CUSTOMER", "read-twice");

        NotificationReadResponse first = mutation.service.markRead(CUSTOMER, id);
        NotificationReadResponse second = mutation.service.markRead(CUSTOMER, id);

        assertThat(first.revision()).isEqualTo(11);
        assertThat(second.revision()).isEqualTo(11);
        assertThat(second.unreadCount()).isZero();
        assertThat(second.notification().status()).isEqualTo(NotificationStatus.READ);
        assertThat(revision(CUSTOMER)).isEqualTo(11);
    }

    @Test
    void deleteOnceAdvancesRevisionAndRemovesVisibilityWithoutChangingContent() throws SQLException {
        String id = insertUnread(CUSTOMER, "CUSTOMER", "delete-once");

        NotificationCountResponse response = mutation.service.delete(CUSTOMER, id);

        assertThat(response.revision()).isEqualTo(11);
        assertThat(response.unreadCount()).isZero();
        assertThat(revision(CUSTOMER)).isEqualTo(11);
        assertThat(visibleCount(id)).isZero();
        assertThat(h.scalarLongDirect("select count(*) from notifications where notification_id = '"
                + id + "' and is_deleted = true")).isEqualTo(1);
        assertThat(messages(id)).containsExactly("delete-once");
    }

    @Test
    void secondDeleteIsNoopAndDoesNotUndelete() throws SQLException {
        String id = insertUnread(CUSTOMER, "CUSTOMER", "delete-twice");

        NotificationCountResponse first = mutation.service.delete(CUSTOMER, id);
        NotificationCountResponse second = mutation.service.delete(CUSTOMER, id);

        assertThat(first.revision()).isEqualTo(11);
        assertThat(second.revision()).isEqualTo(11);
        assertThat(second.unreadCount()).isZero();
        assertThat(revision(CUSTOMER)).isEqualTo(11);
        assertThat(visibleCount(id)).isZero();
        assertThat(messages(id)).containsExactly("delete-twice");
    }

    @Test
    void readAfterDeleteIsNotFound() throws SQLException {
        String id = insertUnread(CUSTOMER, "CUSTOMER", "read-after-delete");
        mutation.service.delete(CUSTOMER, id);

        assertThatThrownBy(() -> mutation.service.markRead(CUSTOMER, id))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(revision(CUSTOMER)).isEqualTo(11);
        assertThat(visibleCount(id)).isZero();
    }

    @Test
    void foreignAndNonexistentIdsAreNotFound() throws SQLException {
        String foreignId = insertUnread(OTHER, "CUSTOMER", "foreign");
        String missing = "NOTI_does-not-exist";

        assertThatThrownBy(() -> mutation.service.markRead(CUSTOMER, foreignId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> mutation.service.delete(CUSTOMER, foreignId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> mutation.service.markRead(CUSTOMER, missing))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> mutation.service.delete(CUSTOMER, missing))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(revision(CUSTOMER)).isEqualTo(10);
        assertThat(visibleCount(foreignId)).isEqualTo(1);
    }

    @Test
    void companyAuthorityLossIsNotAuthorized() throws SQLException {
        setRevision(ADMIN, 10);
        String eligible = insertUnread(ADMIN, "COMPANY", "company-eligible");
        String revoked = insertUnread(ADMIN, "COMPANY", "company-revoked");

        NotificationReadResponse ok = mutation.service.markRead(ADMIN, eligible);
        assertThat(ok.revision()).isEqualTo(11);

        h.execute("delete from role_permissions where role_permission_id = 'rp-admin-order:read'");

        assertThatThrownBy(() -> mutation.service.markRead(ADMIN, revoked))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> mutation.service.delete(ADMIN, revoked))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(revision(ADMIN)).isEqualTo(11);
        assertThat(visibleCount(revoked)).isEqualTo(1);
        assertThat(h.scalarLongDirect("select count(*) from notifications where notification_id = '"
                + revoked + "' and notification_status = 'UNREAD'")).isEqualTo(1);
    }

    @Test
    void insertionCommittedWhileReadWaitsForUserLockIsNotLostOrReset() throws Exception {
        String id = insertUnread(CUSTOMER, "CUSTOMER", "race-read-old");
        Connection holder = h.dataSource.getConnection();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            lockUser(holder, CUSTOMER);
            Future<NotificationReadResponse> pending =
                    worker.submit(() -> mutation.service.markRead(CUSTOMER, id));
            awaitBlockedOnUserLock(1);

            // Fan-out contract: insert and increment happen in the transaction
            // that already locks the recipient user, so they commit together.
            insertRaceRow(holder, "NOTI_race_new", "race-read-new");

            holder.commit();
            NotificationReadResponse response = pending.get(15, TimeUnit.SECONDS);

            assertThat(response.revision()).isEqualTo(12);
            assertThat(response.unreadCount()).isEqualTo(1);
            assertThat(revision(CUSTOMER)).isEqualTo(12);
            assertThat(visibleCount(id)).isEqualTo(1);
            assertThat(visibleCount("NOTI_race_new")).isEqualTo(1);
        } finally {
            rollbackQuietly(holder);
            worker.shutdownNow();
        }
    }

    @Test
    void insertionCommittedWhileDeleteWaitsForUserLockIsNotLostOrReset() throws Exception {
        String id = insertUnread(CUSTOMER, "CUSTOMER", "race-delete-old");
        Connection holder = h.dataSource.getConnection();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            lockUser(holder, CUSTOMER);
            Future<NotificationCountResponse> pending =
                    worker.submit(() -> mutation.service.delete(CUSTOMER, id));
            awaitBlockedOnUserLock(1);

            // The new row and its revision commit in the user-locking fan-out
            // transaction before this mutation gets the lock.
            insertRaceRow(holder, "NOTI_race_new", "race-delete-new");

            holder.commit();
            NotificationCountResponse response = pending.get(15, TimeUnit.SECONDS);

            assertThat(response.revision()).isEqualTo(12);
            assertThat(response.unreadCount()).isEqualTo(1);
            assertThat(revision(CUSTOMER)).isEqualTo(12);
            assertThat(visibleCount(id)).isZero();
            assertThat(visibleCount("NOTI_race_new")).isEqualTo(1);
        } finally {
            rollbackQuietly(holder);
            worker.shutdownNow();
        }
    }

    @Test
    void readThenDeleteAcrossUserLockSerializesToDeletedAtRevision12() throws Exception {
        String id = insertUnread(CUSTOMER, "CUSTOMER", "read-then-delete");
        Connection holder = h.dataSource.getConnection();
        ExecutorService reads = Executors.newSingleThreadExecutor();
        ExecutorService deletes = Executors.newSingleThreadExecutor();
        try {
            lockUser(holder, CUSTOMER);
            Future<NotificationReadResponse> read =
                    reads.submit(() -> mutation.service.markRead(CUSTOMER, id));
            awaitBlockedOnUserLock(1);
            Future<NotificationCountResponse> delete =
                    deletes.submit(() -> mutation.service.delete(CUSTOMER, id));
            awaitBlockedOnUserLock(2);

            holder.commit();
            NotificationReadResponse readResult = read.get(15, TimeUnit.SECONDS);
            NotificationCountResponse deleteResult = delete.get(15, TimeUnit.SECONDS);

            assertThat(readResult.revision()).isEqualTo(11);
            assertThat(deleteResult.revision()).isEqualTo(12);
            assertThat(deleteResult.unreadCount()).isZero();
            assertThat(revision(CUSTOMER)).isEqualTo(12);
            assertThat(readResult.notification().status()).isEqualTo(NotificationStatus.READ);
            assertThat(visibleCount(id)).isZero();
        } finally {
            rollbackQuietly(holder);
            reads.shutdownNow();
            deletes.shutdownNow();
        }
    }

    @Test
    void deleteThenReadAcrossUserLockLeavesReadNotFoundAtRevision11() throws Exception {
        String id = insertUnread(CUSTOMER, "CUSTOMER", "delete-then-read");
        Connection holder = h.dataSource.getConnection();
        ExecutorService deletes = Executors.newSingleThreadExecutor();
        ExecutorService reads = Executors.newSingleThreadExecutor();
        try {
            lockUser(holder, CUSTOMER);
            Future<NotificationCountResponse> delete =
                    deletes.submit(() -> mutation.service.delete(CUSTOMER, id));
            awaitBlockedOnUserLock(1);
            Future<NotificationReadResponse> read =
                    reads.submit(() -> mutation.service.markRead(CUSTOMER, id));
            awaitBlockedOnUserLock(2);

            holder.commit();
            NotificationCountResponse deleteResult = delete.get(15, TimeUnit.SECONDS);
            assertThat(deleteResult.revision()).isEqualTo(11);

            assertThatThrownBy(() -> read.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(ResourceNotFoundException.class);
            assertThat(revision(CUSTOMER)).isEqualTo(11);
            assertThat(visibleCount(id)).isZero();
        } finally {
            rollbackQuietly(holder);
            deletes.shutdownNow();
            reads.shutdownNow();
        }
    }

    private void setRevision(String businessId, long value) throws SQLException {
        h.execute("update users set inbox_revision = " + value + " where user_id = '" + businessId + "'");
    }

    private long revision(String businessId) throws SQLException {
        return h.scalarLongDirect("select inbox_revision from users where user_id = '" + businessId + "'");
    }

    private String insertUnread(String owner, String scope, String message) throws SQLException {
        h.insertEvent(owner, scope, T0, 1L, "UNREAD", message);
        List<String> ids = h.stringsDirect(
                "select notification_id from notifications where notification_message = '" + message + "'");
        assertThat(ids).as("inserted notification %s", message).hasSize(1);
        return ids.getFirst();
    }

    private long visibleCount(String notificationId) throws SQLException {
        return h.scalarLongDirect("select count(*) from notifications where notification_id = '"
                + notificationId + "' and is_deleted = false");
    }

    private List<String> messages(String notificationId) throws SQLException {
        return h.stringsDirect("select notification_message from notifications where notification_id = '"
                + notificationId + "'");
    }

    private void insertRaceRow(Connection connection, String notificationId, String message)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("insert into notifications (notification_id, user_id, "
                    + "notification_message, notification_type, notification_status, "
                    + "source_event_id, order_id, event_type, order_sequence, occurred_at, "
                    + "recipient_scope) values ('" + notificationId
                    + "', (select id from users where user_id = '" + CUSTOMER + "'), '"
                    + message + "', 'ORDER', 'UNREAD', '" + java.util.UUID.randomUUID() + "', "
                    + h.orderSurrogateId + ", 'ORDER_PLACED', 2, '" + T0 + "', 'CUSTOMER')");
            statement.execute("update users set inbox_revision = inbox_revision + 1 "
                    + "where user_id = '" + CUSTOMER + "'");
        }
    }

    private void lockUser(Connection connection, String businessId) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("select id from users where user_id = '" + businessId
                    + "' for update").next();
        }
    }

    private void awaitBlockedOnUserLock(int minimumWaiters) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            long waiters = h.scalarLongDirect("select count(*) from pg_stat_activity "
                    + "where datname = current_database() and wait_event_type = 'Lock' "
                    + "and query ilike '%for update%'");
            if (waiters >= minimumWaiters) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for the user lock", exception);
            }
        }
        fail("Expected at least " + minimumWaiters + " transaction(s) blocked on the user lock");
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort release; holder may already be committed
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort close
        }
    }
}
