package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.dto.response.engagement.NotificationInboxResponse;
import com.project.dacn.dto.response.engagement.NotificationResponse;
import com.project.dacn.dto.response.engagement.NotificationUnreadCountResponse;
import com.project.dacn.support.InboxTestHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.project.dacn.support.InboxTestHarness.ADMIN;
import static com.project.dacn.support.InboxTestHarness.CUSTOMER;
import static com.project.dacn.support.InboxTestHarness.EMPLOYEE;
import static com.project.dacn.support.InboxTestHarness.INACTIVE_PROFILE;
import static com.project.dacn.support.InboxTestHarness.LOCKED;
import static com.project.dacn.support.InboxTestHarness.NOPERM;
import static com.project.dacn.support.InboxTestHarness.OTHER;
import static com.project.dacn.support.InboxTestHarness.PERM;
import static org.assertj.core.api.Assertions.assertThat;

/** Real-PostgreSQL coverage for the bounded repeatable-read inbox snapshot. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class NotificationInboxServicePostgresTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    private static InboxTestHarness h;

    @BeforeAll
    static void startDatabase() throws SQLException {
        h = InboxTestHarness.create();
    }

    @AfterAll
    static void stopDatabase() throws SQLException {
        if (h != null) {
            h.close();
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        h.reset();
    }

    @Test
    void legacyAndCustomerVisibleOnlyToActiveOwner() throws SQLException {
        h.insertLegacy(CUSTOMER, T0, "UNREAD", "legacy-cust");
        h.insertEvent(CUSTOMER, "CUSTOMER", T0.plusSeconds(1), 1L, "UNREAD", "customer-cust");
        h.insertEvent(CUSTOMER, "COMPANY", T0.plusSeconds(2), 2L, "UNREAD", "company-cust");
        h.insertLegacy(OTHER, T0, "UNREAD", "legacy-other");
        h.insertEvent(OTHER, "CUSTOMER", T0.plusSeconds(3), 3L, "UNREAD", "foreign-owned-other");
        h.insertLegacy(LOCKED, T0, "UNREAD", "legacy-locked");

        List<String> messages = messages(h.service.getInbox(CUSTOMER, 0, 20, null));
        assertThat(messages).containsExactlyInAnyOrder("legacy-cust", "customer-cust");
        assertThat(messages).doesNotContain("company-cust", "legacy-other", "legacy-locked");
        assertThat(h.service.unreadCount(CUSTOMER).unreadCount()).isEqualTo(2);
        assertThat(messages(h.service.getInbox(LOCKED, 0, 20, null))).isEmpty();
        assertThat(h.service.unreadCount(LOCKED).unreadCount()).isZero();
    }

    @Test
    void companyRowsVisibleToEligibleAdminAndEmployee() throws SQLException {
        h.insertEvent(ADMIN, "COMPANY", T0, 1L, "UNREAD", "company-admin");
        h.insertEvent(ADMIN, "CUSTOMER", T0, 2L, "UNREAD", "customer-admin");
        h.insertLegacy(ADMIN, T0, "UNREAD", "legacy-admin");
        h.insertEvent(EMPLOYEE, "COMPANY", T0, 1L, "UNREAD", "company-emp");

        assertThat(messages(h.service.getInbox(ADMIN, 0, 20, null)))
                .containsExactlyInAnyOrder("company-admin", "customer-admin", "legacy-admin");
        assertThat(messages(h.service.getInbox(EMPLOYEE, 0, 20, null)))
                .containsExactly("company-emp");
        assertThat(h.service.unreadCount(ADMIN).unreadCount()).isEqualTo(3);
    }

    @Test
    void roleChangeRemovesHistoricalCompanyVisibilityButKeepsCustomerAndLegacy() throws SQLException {
        h.insertEvent(ADMIN, "COMPANY", T0, 1L, "UNREAD", "company-admin");
        h.insertEvent(ADMIN, "CUSTOMER", T0, 2L, "UNREAD", "customer-admin");
        h.insertLegacy(ADMIN, T0, "UNREAD", "legacy-admin");

        assertThat(messages(h.service.getInbox(ADMIN, 0, 20, null))).hasSize(3);

        long customerRole = h.scalarLongDirect(
                "select id from roles where lower(role_code) = 'customer'");
        h.execute("update users set role_id = " + customerRole
                + " where user_id = '" + ADMIN + "'");

        assertThat(messages(h.service.getInbox(ADMIN, 0, 20, null)))
                .containsExactlyInAnyOrder("customer-admin", "legacy-admin")
                .doesNotContain("company-admin");
        assertThat(h.service.unreadCount(ADMIN).unreadCount()).isEqualTo(2);
    }

    @Test
    void permissionRevocationAndInactiveProfileRemoveCompanyVisibility() throws SQLException {
        h.insertEvent(ADMIN, "COMPANY", T0, 1L, "UNREAD", "company-admin");
        h.insertEvent(NOPERM, "COMPANY", T0, 1L, "UNREAD", "company-noperm");
        h.insertEvent(INACTIVE_PROFILE, "COMPANY", T0, 1L, "UNREAD", "company-inactive");
        h.insertEvent(PERM, "COMPANY", T0, 1L, "UNREAD", "company-wrong-role");

        assertThat(messages(h.service.getInbox(ADMIN, 0, 20, null))).containsExactly("company-admin");
        assertThat(messages(h.service.getInbox(NOPERM, 0, 20, null))).isEmpty();
        assertThat(messages(h.service.getInbox(INACTIVE_PROFILE, 0, 20, null))).isEmpty();
        assertThat(messages(h.service.getInbox(PERM, 0, 20, null))).isEmpty();

        h.execute("delete from role_permissions where role_permission_id = 'rp-admin-order:read'");
        assertThat(messages(h.service.getInbox(ADMIN, 0, 20, null))).isEmpty();

        h.execute("update admins set admin_status = 'INACTIVE' where user_id = "
                + "(select id from users where user_id = '" + ADMIN + "')");
        h.execute("insert into role_permissions (role_permission_id, role_id, permission_id) "
                + "select 'rp-admin-order:read', id, (select id from permissions "
                + "where lower(permission_code) = 'order:read') "
                + "from roles where lower(role_code) = 'admin'");
        assertThat(messages(h.service.getInbox(ADMIN, 0, 20, null))).isEmpty();
    }

    @Test
    void accountStatusRevocationHidesEveryScope() throws SQLException {
        h.insertEvent(ADMIN, "COMPANY", T0, 1L, "UNREAD", "company-admin");
        h.insertLegacy(ADMIN, T0, "UNREAD", "legacy-admin");
        h.execute("update users set user_status = 'LOCKED' where user_id = '" + ADMIN + "'");
        assertThat(messages(h.service.getInbox(ADMIN, 0, 20, null))).isEmpty();
        assertThat(h.service.unreadCount(ADMIN).unreadCount()).isZero();
    }

    @Test
    void fixedTieOrderingOccurredAtThenSequenceThenId() throws SQLException {
        h.insertEvent(CUSTOMER, "CUSTOMER", T0.plusSeconds(10), 1L, "UNREAD", "older");
        h.insertEvent(CUSTOMER, "CUSTOMER", T0, 2L, "UNREAD", "seq-2");
        h.insertEvent(CUSTOMER, "CUSTOMER", T0, 1L, "UNREAD", "seq-1");
        h.insertLegacy(CUSTOMER, T0, "UNREAD", "legacy-null-seq");
        h.insertEvent(CUSTOMER, "CUSTOMER", T0, 1L, "UNREAD", "seq-1-later-id");

        List<String> messages = messages(h.service.getInbox(CUSTOMER, 0, 20, null));
        int laterId = messages.indexOf("seq-1-later-id");
        int earlierId = messages.indexOf("seq-1");
        assertThat(laterId).isLessThan(earlierId);
        assertThat(messages).containsExactly("older", "seq-2", "seq-1-later-id", "seq-1",
                "legacy-null-seq");
    }

    @Test
    void eventBackedAndLegacyRowsMapOccurrenceAndNullableFieldsCorrectly() throws SQLException {
        h.insertEvent(ADMIN, "COMPANY", T0, 7L, "READ", "event-row");
        h.insertLegacy(ADMIN, T0.plusSeconds(5), "UNREAD", "legacy-row");

        List<NotificationResponse> rows = h.service.getInbox(ADMIN, 0, 20, null).notifications().getContent();
        NotificationResponse eventRow = row(rows, "event-row");
        assertThat(eventRow.orderId()).isEqualTo("ORD_t4");
        assertThat(eventRow.eventType()).isEqualTo("ORDER_PLACED");
        assertThat(eventRow.orderSequence()).isEqualTo(7L);
        assertThat(eventRow.occurredAt()).isEqualTo(T0);
        assertThat(eventRow.createdAt()).isNotNull();
        assertThat(eventRow.status()).isEqualTo(NotificationStatus.READ);

        NotificationResponse legacyRow = row(rows, "legacy-row");
        assertThat(legacyRow.orderId()).isNull();
        assertThat(legacyRow.eventType()).isNull();
        assertThat(legacyRow.orderSequence()).isNull();
        assertThat(legacyRow.occurredAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(legacyRow.createdAt()).isNotNull();
        assertThat(legacyRow.status()).isEqualTo(NotificationStatus.UNREAD);
    }

    @Test
    void filteredTotalIsScopedButUnreadCountStaysGlobalVisible() throws SQLException {
        for (int i = 0; i < 3; i++) {
            h.insertEvent(CUSTOMER, "CUSTOMER", T0.plusSeconds(i), i + 1L, "UNREAD", "u" + i);
        }
        for (int i = 0; i < 2; i++) {
            h.insertEvent(CUSTOMER, "CUSTOMER", T0.plusSeconds(10 + i), i + 10L, "READ", "r" + i);
        }

        NotificationInboxResponse read = h.service.getInbox(CUSTOMER, 0, 20, NotificationStatus.READ);
        assertThat(read.notifications().getContent()).hasSize(2);
        assertThat(read.notifications().getTotalElements()).isEqualTo(2);
        assertThat(read.unreadCount()).isEqualTo(3);

        NotificationInboxResponse unread = h.service.getInbox(CUSTOMER, 0, 20, NotificationStatus.UNREAD);
        assertThat(unread.notifications().getContent()).hasSize(3);
        assertThat(unread.notifications().getTotalElements()).isEqualTo(3);
        assertThat(unread.unreadCount()).isEqualTo(3);

        NotificationInboxResponse all = h.service.getInbox(CUSTOMER, 0, 20, null);
        assertThat(all.notifications().getContent()).hasSize(5);
        assertThat(all.notifications().getTotalElements()).isEqualTo(5);
        assertThat(all.unreadCount()).isEqualTo(3);
    }

    @Test
    void emptyPagesAndHistoryStayStableAcrossPages() throws SQLException {
        for (int i = 0; i < 55; i++) {
            h.insertEvent(CUSTOMER, "CUSTOMER", T0.plusSeconds(i), i + 1L, "UNREAD", "h" + i);
        }
        NotificationInboxResponse first = h.service.getInbox(CUSTOMER, 0, 20, null);
        NotificationInboxResponse second = h.service.getInbox(CUSTOMER, 1, 20, null);
        NotificationInboxResponse last = h.service.getInbox(CUSTOMER, 2, 20, null);
        NotificationInboxResponse beyond = h.service.getInbox(CUSTOMER, 3, 20, null);

        assertThat(first.notifications().getContent()).hasSize(20);
        assertThat(first.notifications().getTotalElements()).isEqualTo(55);
        assertThat(first.notifications().getTotalPages()).isEqualTo(3);
        assertThat(second.notifications().getContent()).hasSize(20);
        assertThat(last.notifications().getContent()).hasSize(15);
        assertThat(beyond.notifications().getContent()).isEmpty();
        assertThat(beyond.notifications().getTotalElements()).isEqualTo(55);
        assertThat(beyond.unreadCount()).isEqualTo(55);

        List<String> seen = new ArrayList<>();
        seen.addAll(messages(first));
        seen.addAll(messages(second));
        seen.addAll(messages(last));
        assertThat(seen).doesNotHaveDuplicates().hasSize(55);
        assertThat(seen.getFirst()).isEqualTo("h54");
        assertThat(seen.getLast()).isEqualTo("h0");
    }

    @Test
    void serviceGuardCapsSizeAndNormalizesNegativePage() throws SQLException {
        h.insertLegacy(CUSTOMER, T0, "UNREAD", "one");
        NotificationInboxResponse capped = h.service.getInbox(CUSTOMER, -5, 500, null);
        assertThat(capped.notifications().getPage()).isZero();
        assertThat(capped.notifications().getSize()).isEqualTo(NotificationInboxService.MAX_SIZE);
        assertThat(capped.notifications().getContent()).hasSize(1);

        NotificationInboxResponse defaulted = h.service.getInbox(CUSTOMER, 0, 0, null);
        assertThat(defaulted.notifications().getSize()).isEqualTo(NotificationInboxService.DEFAULT_SIZE);
    }

    @Test
    void getInboxUsesExactlyThreeQueriesAndUnreadCountUsesTwo() throws SQLException {
        h.insertEvent(CUSTOMER, "CUSTOMER", T0, 1L, "UNREAD", "one");
        h.service.getInbox(CUSTOMER, 0, 20, NotificationStatus.UNREAD);
        List<String> statements = h.counting.statements();
        assertThat(statements).hasSize(3);
        assertThat(statements).anySatisfy(sql -> assertThat(sql).contains("inbox_revision"));
        assertThat(statements).anySatisfy(sql ->
                assertThat(sql.toLowerCase()).contains("order by n.occurred_at"));
        assertThat(statements).anySatisfy(sql ->
                assertThat(sql.toLowerCase()).contains("count(*) filter"));

        h.counting.reset();
        NotificationUnreadCountResponse count = h.service.unreadCount(CUSTOMER);
        assertThat(count.unreadCount()).isEqualTo(1);
        assertThat(h.counting.statements()).hasSize(2);
    }

    @Test
    void repeatableReadSnapshotIgnoresConcurrentCommitBetweenReads() throws SQLException {
        h.insertEvent(CUSTOMER, "CUSTOMER", T0, 1L, "UNREAD", "before");
        h.counting.onBeforeExecute(sql -> {
            if (sql.toLowerCase().contains("order by n.occurred_at")) {
                try {
                    h.insertEvent(CUSTOMER, "CUSTOMER", T0.plusSeconds(30), 2L, "UNREAD", "after");
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        });

        NotificationInboxResponse snapshot = h.service.getInbox(CUSTOMER, 0, 20, null);
        assertThat(messages(snapshot)).containsExactly("before");
        assertThat(snapshot.unreadCount()).isEqualTo(1);

        h.counting.onBeforeExecute(null);
        NotificationInboxResponse fresh = h.service.getInbox(CUSTOMER, 0, 20, null);
        assertThat(messages(fresh)).containsExactly("after", "before");
        assertThat(fresh.unreadCount()).isEqualTo(2);
    }

    @Test
    void visibleInboxIndexChosenOnRepresentativeHistory() throws SQLException {
        h.execute("insert into notifications (notification_id, user_id, notification_message, "
                + "notification_type, notification_status, occurred_at) "
                + "select 'NOTI_gen_' || g, (select id from users where user_id = '" + CUSTOMER + "'), "
                + "'bulk', 'SYSTEM', 'UNREAD', '" + T0 + "'::timestamptz - g * interval '1 second' "
                + "from generate_series(1, 1500) g");

        String explain = "explain select n.id, n.notification_id, u.user_id, n.notification_message, "
                + "n.notification_type, n.notification_status, n.created_at, n.updated_at, "
                + "o.order_id, n.event_type, n.order_sequence, n.occurred_at "
                + "from notifications n join users u on u.id = n.user_id "
                + "left join orders o on o.id = n.order_id "
                + "where n.user_id = (select id from users where user_id = '" + CUSTOMER + "') "
                + "and n.is_deleted = false "
                + "and (n.recipient_scope is null or n.recipient_scope = 'CUSTOMER' "
                + "or (true and n.recipient_scope = 'COMPANY')) "
                + "and (cast(null as varchar(20)) is null "
                + "or n.notification_status = cast(null as varchar(20))) "
                + "order by n.occurred_at desc, n.order_sequence desc nulls last, n.id desc "
                + "limit 20 offset 0";

        List<String> plan = strings(explain);
        assertThat(plan).anySatisfy(line -> assertThat(line).contains("idx_notifications_inbox_visible"));
    }

    private static NotificationResponse row(List<NotificationResponse> rows, String message) {
        return rows.stream().filter(r -> r.message().equals(message)).findFirst().orElseThrow();
    }

    private static List<String> messages(NotificationInboxResponse response) {
        return response.notifications().getContent().stream().map(NotificationResponse::message).toList();
    }

    private static List<String> strings(String sql) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Connection connection = h.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                lines.add(rows.getString(1));
            }
        }
        return lines;
    }
}
