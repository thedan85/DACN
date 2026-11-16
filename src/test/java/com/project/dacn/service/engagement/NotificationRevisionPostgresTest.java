package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.dto.response.engagement.NotificationCountResponse;
import com.project.dacn.dto.response.engagement.NotificationReadResponse;
import com.project.dacn.entity.auth.User;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.support.FanoutTestHarness;
import com.project.dacn.support.MutationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static com.project.dacn.common.constant.PaymentMethod.VIETQR;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class NotificationRevisionPostgresTest {

    private static final String CUSTOMER_BUSINESS_ID = "USR_t3_cust";

    private static FanoutTestHarness h;
    private static MutationTestSupport mutation;

    @BeforeAll
    static void setUp() throws SQLException {
        h = FanoutTestHarness.create();
        mutation = MutationTestSupport.create(h.dataSource);
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (mutation != null) {
            mutation.close();
        }
        if (h != null) {
            h.close();
        }
    }

    /**
     * Transaction A loads the User without locking, B commits real fan-out to
     * revision 11 with one unread row, then A performs an ordinary auth-style
     * save/flush. A's ordinary field must persist without moving the
     * database-owned revision or losing the notification.
     */
    @Test
    void staleUserSaveDoesNotClobberFanoutRevisionOrNotification() throws Exception {
        h.execute("update users set inbox_revision = 10 where id = " + h.customerId);
        h.execute("delete from notifications where source_event_id is not null");
        h.execute("delete from notification_event_receipts");

        EntityManagerFactory factory = buildEntityManagerFactory();
        try {
            EntityManager entityManagerA = factory.createEntityManager();
            SimpleJpaRepository<User, Long> userRepository =
                    new SimpleJpaRepository<>(User.class, entityManagerA);
            EntityTransaction transactionA = entityManagerA.getTransaction();
            transactionA.begin();
            User loaded = userRepository.findById(h.customerId).orElseThrow();
            assertThat(loaded.getInboxRevision()).isEqualTo(10);

            UUID eventId = UUID.randomUUID();
            OrderEvent event = h.event(eventId, h.customerId, h.dbNow.minusSeconds(60),
                    VIETQR, h.orderId);

            // B: complete in-app fan-out with its own transaction.
            h.handle(event);

            // A: ordinary auth-style update using the stale managed User.
            Instant newLastLogin = Instant.now();
            loaded.setLastLogin(newLastLogin);
            userRepository.save(loaded);
            entityManagerA.flush();
            transactionA.commit();
            entityManagerA.close();

            assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                    .isEqualTo(11);
            List<String> lastLogins = h.strings(
                    "select user_last_login from users where id = " + h.customerId);
            assertThat(lastLogins).hasSize(1);
            assertThat(lastLogins.getFirst()).as("A's ordinary field persisted").isNotBlank();
            assertThat(h.scalarLong("select count(*) from notifications where user_id = "
                    + h.customerId + " and notification_status = 'UNREAD' and is_deleted = false"))
                    .isEqualTo(1);
            assertThat(h.scalarLong("select count(*) from notifications "
                    + "where source_event_id = '" + eventId + "'")).isEqualTo(1);

            // Replay must not add a second row or advance the revision.
            h.handle(event);
            assertThat(h.scalarLong("select count(*) from notifications "
                    + "where source_event_id = '" + eventId + "'")).isEqualTo(1);
            assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                    .isEqualTo(11);
        } finally {
            factory.close();
        }
    }

    /**
     * A real committed READ mutation between A's stale User load and A's
     * ordinary save must survive unchanged: revision 11, unread 0, visible 1.
     */
    @Test
    void staleUserSavePreservesCommittedReadResult() throws Exception {
        h.execute("update users set inbox_revision = 10 where id = " + h.customerId);
        h.execute("delete from notifications where source_event_id is not null");
        String id = insertUnreadRow("NOTI_t5_read", "t5-read");

        EntityManagerFactory factory = buildEntityManagerFactory();
        try {
            EntityManager entityManagerA = factory.createEntityManager();
            SimpleJpaRepository<User, Long> userRepository =
                    new SimpleJpaRepository<>(User.class, entityManagerA);
            EntityTransaction transactionA = entityManagerA.getTransaction();
            transactionA.begin();
            User loaded = userRepository.findById(h.customerId).orElseThrow();
            assertThat(loaded.getInboxRevision()).isEqualTo(10);

            NotificationReadResponse read = mutation.service.markRead(CUSTOMER_BUSINESS_ID, id);
            assertThat(read.revision()).isEqualTo(11);
            assertThat(read.unreadCount()).isZero();
            assertThat(read.notification().status()).isEqualTo(NotificationStatus.READ);

            loaded.setLastLogin(Instant.now());
            userRepository.save(loaded);
            entityManagerA.flush();
            transactionA.commit();
            entityManagerA.close();

            assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                    .isEqualTo(11);
            assertThat(h.scalarLong("select count(*) from notifications where user_id = "
                    + h.customerId + " and notification_status = 'UNREAD' and is_deleted = false"))
                    .isZero();
            assertThat(h.scalarLong("select count(*) from notifications where notification_id = '"
                    + id + "' and is_deleted = false")).isEqualTo(1);
            assertThat(h.strings("select user_last_login from users where id = " + h.customerId))
                    .hasSize(1).first().asString().isNotBlank();

            NotificationReadResponse duplicate = mutation.service.markRead(CUSTOMER_BUSINESS_ID, id);
            assertThat(duplicate.revision()).isEqualTo(11);
            assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                    .isEqualTo(11);
        } finally {
            factory.close();
        }
    }

    /** A real committed DELETE mutation leaves revision 11, unread 0, visible 0. */
    @Test
    void staleUserSavePreservesCommittedDeleteResult() throws Exception {
        h.execute("update users set inbox_revision = 10 where id = " + h.customerId);
        h.execute("delete from notifications where source_event_id is not null");
        String id = insertUnreadRow("NOTI_t5_delete", "t5-delete");

        EntityManagerFactory factory = buildEntityManagerFactory();
        try {
            EntityManager entityManagerA = factory.createEntityManager();
            SimpleJpaRepository<User, Long> userRepository =
                    new SimpleJpaRepository<>(User.class, entityManagerA);
            EntityTransaction transactionA = entityManagerA.getTransaction();
            transactionA.begin();
            User loaded = userRepository.findById(h.customerId).orElseThrow();
            assertThat(loaded.getInboxRevision()).isEqualTo(10);

            NotificationCountResponse deleted = mutation.service.delete(CUSTOMER_BUSINESS_ID, id);
            assertThat(deleted.revision()).isEqualTo(11);
            assertThat(deleted.unreadCount()).isZero();

            loaded.setLastLogin(Instant.now());
            userRepository.save(loaded);
            entityManagerA.flush();
            transactionA.commit();
            entityManagerA.close();

            assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                    .isEqualTo(11);
            assertThat(h.scalarLong("select count(*) from notifications where user_id = "
                    + h.customerId + " and notification_status = 'UNREAD' and is_deleted = false"))
                    .isZero();
            assertThat(h.scalarLong("select count(*) from notifications where notification_id = '"
                    + id + "' and is_deleted = false")).isZero();
            assertThat(h.strings("select user_last_login from users where id = " + h.customerId))
                    .hasSize(1).first().asString().isNotBlank();

            NotificationCountResponse duplicate = mutation.service.delete(CUSTOMER_BUSINESS_ID, id);
            assertThat(duplicate.revision()).isEqualTo(11);
            assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                    .isEqualTo(11);
        } finally {
            factory.close();
        }
    }

    /**
     * A stale writer held from an empty inbox at revision 10 across a real
     * fan-out (11), READ (12), and DELETE (13) sequence never resets to 10.
     */
    @Test
    void staleWriterAcrossFanoutReadDeleteEndsAtRevision13() throws Exception {
        h.execute("update users set inbox_revision = 10 where id = " + h.customerId);
        h.execute("delete from notifications where source_event_id is not null");
        h.execute("delete from notification_event_receipts");
        assertThat(h.scalarLong("select count(*) from notifications where user_id = "
                + h.customerId)).isZero();

        EntityManagerFactory factory = buildEntityManagerFactory();
        try {
            EntityManager entityManagerA = factory.createEntityManager();
            SimpleJpaRepository<User, Long> userRepository =
                    new SimpleJpaRepository<>(User.class, entityManagerA);
            EntityTransaction transactionA = entityManagerA.getTransaction();
            transactionA.begin();
            User loaded = userRepository.findById(h.customerId).orElseThrow();
            assertThat(loaded.getInboxRevision()).isEqualTo(10);

            UUID eventId = UUID.randomUUID();
            OrderEvent event = h.event(eventId, h.customerId, h.dbNow.minusSeconds(60),
                    VIETQR, h.orderId);
            h.handle(event);
            String id = h.strings("select notification_id from notifications where source_event_id = '"
                    + eventId + "'").getFirst();
            NotificationReadResponse read = mutation.service.markRead(CUSTOMER_BUSINESS_ID, id);
            NotificationCountResponse deleted = mutation.service.delete(CUSTOMER_BUSINESS_ID, id);
            assertThat(read.revision()).isEqualTo(12);
            assertThat(deleted.revision()).isEqualTo(13);

            loaded.setLastLogin(Instant.now());
            userRepository.save(loaded);
            entityManagerA.flush();
            transactionA.commit();
            entityManagerA.close();

            assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                    .isEqualTo(13);
            assertThat(h.scalarLong("select count(*) from notifications where user_id = "
                    + h.customerId + " and notification_status = 'UNREAD' and is_deleted = false"))
                    .isZero();
            assertThat(h.scalarLong("select count(*) from notifications where notification_id = '"
                    + id + "' and is_deleted = false")).isZero();
        } finally {
            factory.close();
        }
    }

    private String insertUnreadRow(String notificationId, String message) throws SQLException {
        h.execute("insert into notifications (notification_id, user_id, notification_message, "
                + "notification_type, notification_status, source_event_id, order_id, "
                + "event_type, order_sequence, occurred_at, recipient_scope) "
                + "values ('" + notificationId + "', " + h.customerId + ", '" + message
                + "', 'ORDER', 'UNREAD', '" + UUID.randomUUID() + "', " + h.orderSurrogateId
                + ", 'ORDER_PLACED', 1, '" + h.dbNow + "', 'CUSTOMER')");
        return notificationId;
    }

    private static EntityManagerFactory buildEntityManagerFactory() {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(h.dataSource);
        bean.setPackagesToScan("com.project.dacn.entity");
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.hbm2ddl.auto", "none");
        bean.setJpaProperties(properties);
        bean.afterPropertiesSet();
        return bean.getObject();
    }
}
