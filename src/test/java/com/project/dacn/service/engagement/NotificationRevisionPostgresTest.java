package com.project.dacn.service.engagement;

import com.project.dacn.entity.auth.User;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.support.FanoutTestHarness;
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
