package com.project.dacn.service.engagement;

import com.project.dacn.dto.response.engagement.NotificationCountResponse;
import com.project.dacn.dto.response.engagement.NotificationReadResponse;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.support.FanoutTestHarness;
import com.project.dacn.support.MutationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static com.project.dacn.common.constant.PaymentMethod.VIETQR;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3/5 stale ordinary-User races rerun with the real inbox hint trigger installed.
 *
 * <p>After real fan-out/read/delete, queued hints are inspected before dispatch: only
 * changed revisions appear, the late User flush adds none, and the budgeted dispatcher
 * coalesces to the highest revision. A sending-disabled run keeps revision and count
 * correct without hints.</p>
 */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationRevisionHintPostgresTest {

    private static final String CUSTOMER_BUSINESS_ID = "USR_t3_cust";

    /** Local scratch PG has no Supabase cluster roles; create the prerequisites the operator script checks. */
    private static final String ROLE_PRECONDITION = """
            do $$ begin
              if not exists (select 1 from pg_roles where rolname = 'anon') then
                create role anon nologin;
              end if;
              if not exists (select 1 from pg_roles where rolname = 'authenticated') then
                create role authenticated nologin;
              end if;
            end $$;
            """;

    private static final String STUBS = """
            create schema if not exists realtime;
            create table if not exists realtime.messages (
                id bigint generated always as identity primary key,
                extension text, topic text, event text, payload jsonb,
                private boolean not null default true
            );
            create table if not exists public.realtime_test_capture (
                topic text, event text, payload jsonb, private boolean,
                captured_at timestamptz not null default clock_timestamp()
            );
            create or replace function realtime.topic() returns text
                language sql stable as $$
                    select coalesce(current_setting('realtime.topic', true), '')
                $$;
            create or replace function realtime.send(
                    payload jsonb, event text, topic text, private boolean)
            returns void language sql as $$
                insert into public.realtime_test_capture (topic, event, payload, private)
                values (topic, event, payload, private)
            $$;
            """;

    private static FanoutTestHarness h;
    private static MutationTestSupport mutation;

    @BeforeAll
    static void setUp() throws Exception {
        h = FanoutTestHarness.create();
        mutation = MutationTestSupport.create(h.dataSource);

        String triggerScript = Files.readString(Path.of(
                "src/main/resources/db/supabase/order_notifications_realtime.sql"));
        String foundationScript = Files.readString(Path.of(
                "src/main/resources/db/supabase/notification_realtime.sql"));

        try (Connection connection = h.connect(); Statement statement = connection.createStatement()) {
            statement.execute(triggerScript);
            statement.execute(triggerScript);
            statement.execute(STUBS);
            statement.execute(ROLE_PRECONDITION);
            statement.execute(foundationScript);
        }
        System.out.println("NotificationRevisionHint scratch identity: " + h.scratchDatabaseName);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (mutation != null) {
            mutation.close();
        }
        if (h != null) {
            h.close();
        }
    }

    @Test
    @Order(1)
    void staleWriterAcrossFanoutReadDeleteQueuesOnlyChangedRevisionsAndCoalesces() throws Exception {
        resetScenario();
        setControl(true, 1000);
        insertInboxLease();

        EntityManagerFactory factory = buildEntityManagerFactory();
        try {
            EntityManager entityManagerA = factory.createEntityManager();
            SimpleJpaRepository<com.project.dacn.entity.auth.User, Long> userRepository =
                    new SimpleJpaRepository<>(com.project.dacn.entity.auth.User.class, entityManagerA);
            EntityTransaction transactionA = entityManagerA.getTransaction();
            transactionA.begin();
            com.project.dacn.entity.auth.User loaded =
                    userRepository.findById(h.customerId).orElseThrow();
            assertThat(loaded.getInboxRevision()).isEqualTo(10);

            UUID eventId = UUID.randomUUID();
            OrderEvent event = h.event(eventId, h.customerId, h.dbNow.minusSeconds(60),
                    VIETQR, h.orderId);

            // B: real fan-out -> revision 11, one unread row.
            h.handle(event);
            String id = h.strings("select notification_id from notifications "
                    + "where source_event_id = '" + eventId + "'").getFirst();

            // Real READ -> 12, real DELETE -> 13.
            NotificationReadResponse read = mutation.service.markRead(CUSTOMER_BUSINESS_ID, id);
            NotificationCountResponse deleted = mutation.service.delete(CUSTOMER_BUSINESS_ID, id);
            assertThat(read.revision()).isEqualTo(12);
            assertThat(deleted.revision()).isEqualTo(13);

            // A: stale ordinary auth-style save; must not move revision or add a hint.
            loaded.setLastLogin(Instant.now());
            userRepository.save(loaded);
            entityManagerA.flush();
            transactionA.commit();
            entityManagerA.close();

            // Inspect queued hints before dispatch.
            List<Long> hintRevisions = h.longs("""
                    select (payload ->> 'revision')::bigint
                      from realtime_signal_outbox
                     where topic = 'inbox:%d'
                       and event_name = 'inbox.changed'
                       and sent_at is null
                     order by id
                    """.formatted(h.customerId));
            assertThat(hintRevisions).as("only fan-out/read/delete revisions, no revision 10")
                    .containsExactly(11L, 12L, 13L);
            assertThat(h.scalarLong("select count(*) from realtime_signal_outbox "
                    + "where sent_at is null")).isEqualTo(3);
            assertThat(h.scalarLong("select count(*) from realtime_signal_outbox "
                    + "where (payload ->> 'revision')::bigint = 10")).isZero();
            assertThat(h.strings("select to_jsonb(h)::text from realtime_signal_outbox h "
                    + "where to_jsonb(h)::text ~* '(customer|email|unread|order|notification)'")).isEmpty();

            // Dispatcher coalesces to the highest revision and sends once.
            Integer sent = h.jdbc.queryForObject(
                    "select app_private.dispatch_realtime_signals()", Integer.class);
            assertThat(sent).isEqualTo(1);
            assertThat(h.scalarLong("select count(*) from realtime_test_capture "
                    + "where event = 'inbox.changed' and private "
                    + "and topic = 'inbox:" + h.customerId + "'")).isEqualTo(1);
            assertThat(h.strings("select payload ->> 'revision' from realtime_test_capture")).containsExactly("13");
            assertThat(h.scalarLong("select count(*) from realtime_signal_outbox "
                    + "where sent_at is null")).isZero();

            // REST snapshot still agrees with SQL revision and count.
            assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                    .isEqualTo(13);
            var snapshot = mutation.service.unreadCount(CUSTOMER_BUSINESS_ID);
            assertThat(snapshot.revision()).isEqualTo(13);
            assertThat(snapshot.unreadCount()).isZero();
            var inbox = mutation.service.getInbox(CUSTOMER_BUSINESS_ID, 0, 20, null);
            assertThat(inbox.revision()).isEqualTo(13);
            assertThat(inbox.unreadCount()).isZero();
            assertThat(inbox.notifications().getTotalElements()).isZero();
        } finally {
            factory.close();
        }
    }

    @Test
    @Order(2)
    void sendingDisabledKeepsRevisionAndCountCorrectWithoutHints() throws Exception {
        resetScenario();
        setControl(false, 0);

        UUID eventId = UUID.randomUUID();
        OrderEvent event = h.event(eventId, h.customerId, h.dbNow.minusSeconds(60),
                VIETQR, h.orderId);
        h.handle(event);
        String id = h.strings("select notification_id from notifications "
                + "where source_event_id = '" + eventId + "'").getFirst();
        NotificationReadResponse read = mutation.service.markRead(CUSTOMER_BUSINESS_ID, id);
        NotificationCountResponse deleted = mutation.service.delete(CUSTOMER_BUSINESS_ID, id);

        assertThat(read.revision()).isEqualTo(12);
        assertThat(deleted.revision()).isEqualTo(13);
        assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                .isEqualTo(13);
        assertThat(h.scalarLong("select count(*) from notifications where user_id = "
                + h.customerId + " and notification_status = 'UNREAD' and is_deleted = false")).isZero();
        assertThat(h.scalarLong("select count(*) from realtime_signal_outbox where sent_at is null"))
                .as("disabled and zero budget preserve correctness without hints").isZero();
        assertThat(h.scalarLong("select count(*) from realtime_test_capture")).isZero();

        var snapshot = mutation.service.unreadCount(CUSTOMER_BUSINESS_ID);
        assertThat(snapshot.revision()).isEqualTo(13);
        assertThat(snapshot.unreadCount()).isZero();
    }

    private static void resetScenario() throws Exception {
        h.execute("update users set inbox_revision = 10 where id = " + h.customerId);
        h.execute("delete from notifications where source_event_id is not null");
        h.execute("delete from notification_event_receipts");
        h.execute("truncate table realtime_signal_outbox, realtime_send_accounting, "
                + "realtime_token_leases, realtime_test_capture restart identity");
        assertThat(h.scalarLong("select inbox_revision from users where id = " + h.customerId))
                .isEqualTo(10);
    }

    private static void setControl(boolean enabled, long budget) throws Exception {
        h.execute("update app_realtime_control set enabled = " + enabled
                + ", remaining_message_budget = " + budget + ", updated_at = clock_timestamp() "
                + "where id = 1");
    }

    private static void insertInboxLease() throws Exception {
        h.execute("""
                insert into realtime_token_leases
                    (jti, user_id, admitted_product_ids, inbox_allowed, expires_at, retire_at)
                values ('%s', %d, '[]'::jsonb, true,
                        clock_timestamp() - interval '1 second',
                        clock_timestamp() + interval '1 hour')
                """.formatted(UUID.randomUUID(), h.customerId));
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
