package com.project.dacn.db;

import com.project.dacn.support.ExternalPostgresDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL proof for the operator-stage inventory trigger script.
 *
 * <p>The whole class runs in an invocation-owned {@code verify_event_foundation_<12 hex>}
 * scratch database. It installs the script twice, exercises the full stock/visibility
 * matrix, and proves that no Supabase {@code realtime} schema, browser roles, or
 * notification-stage objects are required.</p>
 */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StockSignalTriggersPostgresTest {

    private static final Set<String> TRIGGER_NAMES = new LinkedHashSet<>(Set.of(
            "variants_stock_signal",
            "variants_visibility_signal",
            "products_visibility_signal"));

    private static ExternalPostgresDatabase database;
    private static String scratchName;
    private static String script;

    @BeforeAll
    static void prepareOwnedScratchDatabase() throws Exception {
        database = ExternalPostgresDatabase.create();
        scratchName = URI.create(database.jdbcUrl().substring("jdbc:".length()))
                .getPath().substring(1);
        assertThat(scratchName).startsWith("verify_event_foundation_");
        assertThat(scratchName).matches("verify_event_foundation_[0-9a-f]{12}");

        try (Connection connection = connect()) {
            assertThat(currentDatabase(connection)).isEqualTo(scratchName);
            assertThat(count(connection,
                    "select count(*) from pg_namespace where nspname = 'realtime'"))
                    .as("A plain PostgreSQL scratch database has no realtime schema before installation")
                    .isZero();
            System.out.println("StockSignalTriggers scratch identity: " + scratchName);
        }

        database.migrate();
        script = Files.readString(Path.of(
                "src/main/resources/db/supabase/inventory_realtime.sql"));

        try (Connection connection = connect()) {
            assertThat(currentDatabase(connection)).isEqualTo(scratchName);
            assertThat(count(connection, """
                    select count(*) from flyway_schema_history
                     where version = '35' and success
                    """)).isEqualTo(1);
            assertThat(count(connection, """
                    select count(*) from flyway_schema_history
                     where version in ('33', '34', '35') and success
                    """)).isEqualTo(3);
            assertThat(count(connection, """
                    select count(*) from flyway_schema_history
                     where version not in ('1','2','3','4','5','6','7','8','9','10','11','12','13',
                                           '14','15','16','17','18','19','20','21','22','23','24',
                                           '25','26','27','28','29','30','31','32','33','34','35','36')
                       and success
                    """)).as("V36 remains the highest migration").isZero();
            assertThat(count(connection,
                    "select count(*) from pg_namespace where nspname = 'realtime'"))
                    .as("Flyway does not create the Supabase realtime schema")
                    .isZero();

            execute(connection, script);
            execute(connection, script);

            assertThat(count(connection,
                    "select count(*) from pg_namespace where nspname = 'realtime'"))
                    .as("The operator script must not require or create the realtime schema")
                    .isZero();
            System.out.println("StockSignalTriggers PostgreSQL evidence: Flyway V34/V35/V36 successful, "
                    + "realtime schema absent before and after installation, triggers="
                    + operatorTriggerCount(connection));
        }
    }

    @AfterAll
    static void dropOnlyOwnedScratchDatabase() throws Exception {
        if (database == null) {
            return;
        }
        database.drop();
        try (Connection connection = DriverManager.getConnection(
                System.getenv("TEST_POSTGRES_URL"), database.username(), database.password());
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from pg_database where datname = ?")) {
            statement.setString(1, scratchName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1))
                        .as("Only this invocation's scratch database is removed")
                        .isZero();
            }
        }
        System.out.println("StockSignalTriggers scratch cleanup: " + scratchName + "; remaining=0");
    }

    @BeforeEach
    void resetFixturesAndControl() throws SQLException {
        try (Connection connection = connect()) {
            assertThat(currentDatabase(connection)).isEqualTo(scratchName);
            execute(connection, """
                    truncate table public.realtime_signal_outbox,
                                   public.realtime_send_accounting,
                                   public.realtime_token_leases,
                                   public.media,
                                   public.variant_attribute_values,
                                   public.cart_items,
                                   public.order_items,
                                   public.reviews,
                                   public.variants,
                                   public.product_attribute_values,
                                   public.products
                    restart identity cascade
                    """);
            execute(connection, """
                    update public.app_realtime_control
                       set enabled = true,
                           remaining_message_budget = 1000,
                           updated_at = clock_timestamp()
                     where id = 1
                    """);
            assertThat(count(connection,
                    "select count(*) from public.app_realtime_control where id = 1"))
                    .isEqualTo(1);
        }
    }

    @Test
    @Order(1)
    void scriptReappliesWithoutDuplicatesAndLeavesV35Untouched() throws SQLException {
        try (Connection connection = connect()) {
            assertOperatorInstallShape(connection);

            String revisionFunction = string(connection, """
                    select pg_get_functiondef('public.advance_variant_stock_revision()'::regprocedure)
                    """);
            long revisionFunctionOid = count(connection, """
                    select oid from pg_proc
                     where oid = 'public.advance_variant_stock_revision()'::regprocedure
                    """);
            long revisionTriggerOid = count(connection, """
                    select oid from pg_trigger
                     where tgname = 'variants_stock_revision'
                       and tgrelid = 'public.variants'::regclass
                    """);
            long relationCount = count(connection, """
                    select count(*) from pg_class c
                      join pg_namespace n on n.oid = c.relnamespace
                     where n.nspname = 'public'
                    """);

            execute(connection, script);
            execute(connection, script);

            assertOperatorInstallShape(connection);
            assertThat(count(connection, """
                    select count(*) from pg_class c
                      join pg_namespace n on n.oid = c.relnamespace
                     where n.nspname = 'public'
                    """)).as("Reapply creates no new relations").isEqualTo(relationCount);
            assertThat(string(connection, """
                    select pg_get_functiondef('public.advance_variant_stock_revision()'::regprocedure)
                    """)).isEqualTo(revisionFunction);
            assertThat(count(connection, """
                    select oid from pg_proc
                     where oid = 'public.advance_variant_stock_revision()'::regprocedure
                    """)).isEqualTo(revisionFunctionOid);
            assertThat(count(connection, """
                    select oid from pg_trigger
                     where tgname = 'variants_stock_revision'
                       and tgrelid = 'public.variants'::regclass
                    """)).isEqualTo(revisionTriggerOid);
            assertThat(count(connection,
                    "select count(*) from pg_namespace where nspname = 'realtime'"))
                    .isZero();
        }
    }

    @Test
    @Order(2)
    void changedVisibleStockEnqueuesOneExactMinimalHintForA255CharacterBusinessId() throws Exception {
        String productId = "p".repeat(255);
        try (Connection connection = connect()) {
            long product = product(connection, productId, "ACTIVE");
            long variant = variant(connection, "variant-long", product, 10, false);

            execute(connection, "update public.variants set variant_stock = 7 where id = ?", variant);

            assertThat(pendingHints(connection)).isEqualTo(1);
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where topic = ?
                       and event_name = 'stock.changed'
                       and payload = jsonb_build_object('productId', ?)
                       and payload - 'productId' = '{}'::jsonb
                       and (select count(*) from jsonb_object_keys(payload)) = 1
                       and octet_length(payload::text) <= 1024
                       and sent_at is null
                       and reserved_message_cost is null
                    """, "stock:" + productId, productId)).isEqualTo(1);
            assertThat(count(connection,
                    "select length(topic) from public.realtime_signal_outbox"))
                    .isEqualTo("stock:".length() + 255);
            assertThat(count(connection,
                    "select octet_length(payload::text) from public.realtime_signal_outbox"))
                    .isBetween(255L, 1024L);
            assertThat(stock(connection, variant)).isEqualTo(7);
            assertThat(revision(connection, variant)).isEqualTo(1);
        }
    }

    @Test
    @Order(3)
    void noOpStockUpdateEnqueuesNothingAndDoesNotAdvanceRevision() throws Exception {
        try (Connection connection = connect()) {
            long product = product(connection, "noop-product", "ACTIVE");
            long variant = variant(connection, "variant-noop", product, 10, false);

            execute(connection, "update public.variants set variant_stock = 7 where id = ?", variant);
            assertThat(pendingHints(connection)).isEqualTo(1);
            assertThat(revision(connection, variant)).isEqualTo(1);

            execute(connection, "update public.variants set variant_stock = 7 where id = ?", variant);

            assertThat(pendingHints(connection)).isEqualTo(1);
            assertThat(revision(connection, variant)).isEqualTo(1);
            assertThat(stock(connection, variant)).isEqualTo(7);
        }
    }

    @Test
    @Order(4)
    void variantVisibilityEnqueuesOnlyForAnActiveParentOnDeleteAndRestore() throws Exception {
        try (Connection connection = connect()) {
            long active = product(connection, "active-visibility", "ACTIVE");
            long activeVariant = variant(connection, "variant-active-visibility", active, 10, false);
            long hidden = product(connection, "hidden-visibility", "DRAFT");
            long hiddenVariant = variant(connection, "variant-hidden-visibility", hidden, 10, false);

            execute(connection, "update public.variants set is_deleted = true where id = ?",
                    activeVariant);
            assertThat(pendingHints(connection)).isEqualTo(1);
            execute(connection, "update public.variants set is_deleted = false where id = ?",
                    activeVariant);
            assertThat(pendingHints(connection)).isEqualTo(2);
            assertThat(revision(connection, activeVariant)).isZero();

            execute(connection, "update public.variants set is_deleted = true where id = ?",
                    hiddenVariant);
            execute(connection, "update public.variants set is_deleted = false where id = ?",
                    hiddenVariant);
            assertThat(pendingHints(connection)).isEqualTo(2);
            assertThat(revision(connection, hiddenVariant)).isZero();

            execute(connection, "update public.variants set is_deleted = false where id = ?",
                    activeVariant);
            assertThat(pendingHints(connection)).isEqualTo(2);
        }
    }

    @Test
    @Order(5)
    void deletedVariantLaterStockChangeStaysSilentWhileItsVisibilityStillEnqueues() throws Exception {
        try (Connection connection = connect()) {
            long product = product(connection, "deleted-later-product", "ACTIVE");
            long variant = variant(connection, "variant-deleted-later", product, 10, false);

            execute(connection, "update public.variants set is_deleted = true where id = ?", variant);
            assertThat(pendingHints(connection)).isEqualTo(1);

            execute(connection, "update public.variants set variant_stock = 4 where id = ?", variant);
            assertThat(pendingHints(connection)).isEqualTo(1);
            assertThat(stock(connection, variant)).isEqualTo(4);
            assertThat(revision(connection, variant)).isEqualTo(1);

            execute(connection, "update public.variants set is_deleted = false where id = ?", variant);
            assertThat(pendingHints(connection)).isEqualTo(2);

            execute(connection, "update public.variants set variant_stock = 3 where id = ?", variant);
            assertThat(pendingHints(connection)).isEqualTo(3);
            assertThat(revision(connection, variant)).isEqualTo(2);
        }
    }

    @Test
    @Order(6)
    void productVisibilityEnqueuesOnlyTransitionsTouchingActive() throws Exception {
        try (Connection connection = connect()) {
            long activeToDraft = product(connection, "active-to-draft", "ACTIVE");
            long draftToActive = product(connection, "draft-to-active", "DRAFT");
            long activeToDeleted = product(connection, "active-to-deleted", "ACTIVE");
            long hiddenToHidden = product(connection, "hidden-to-hidden", "DRAFT");
            long inactiveToDeleted = product(connection, "inactive-to-deleted", "INACTIVE");
            long hiddenVariant = variant(connection, "variant-hidden-stock", activeToDraft, 10, false);

            execute(connection, "update public.products set product_status = 'DRAFT' where id = ?",
                    activeToDraft);
            execute(connection, "update public.products set product_status = 'ACTIVE' where id = ?",
                    draftToActive);
            execute(connection, "update public.products set product_status = 'DELETED' where id = ?",
                    activeToDeleted);
            execute(connection, "update public.products set product_status = 'DELETED' where id = ?",
                    hiddenToHidden);
            execute(connection, "update public.products set product_status = 'DRAFT' where id = ?",
                    inactiveToDeleted);
            execute(connection, "update public.products set product_status = 'DRAFT' where id = ?",
                    hiddenToHidden);

            assertThat(pendingHints(connection)).isEqualTo(3);
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where topic = 'stock:active-to-draft'
                    """)).isEqualTo(1);
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where topic = 'stock:draft-to-active'
                    """)).isEqualTo(1);
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where topic = 'stock:active-to-deleted'
                    """)).isEqualTo(1);
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where topic in ('stock:hidden-to-hidden', 'stock:inactive-to-deleted')
                    """)).isZero();

            execute(connection, "update public.variants set variant_stock = 9 where id = ?",
                    hiddenVariant);
            assertThat(pendingHints(connection)).isEqualTo(3);
            assertThat(revision(connection, hiddenVariant)).isEqualTo(1);
        }
    }

    @Test
    @Order(7)
    void rollbackRemovesTheOutboxHintAndTheRevisionIncrement() throws Exception {
        try (Connection connection = connect()) {
            long product = product(connection, "rollback-product", "ACTIVE");
            long variant = variant(connection, "variant-rollback", product, 10, false);

            connection.setAutoCommit(false);
            try {
                execute(connection, "update public.variants set variant_stock = 2 where id = ?",
                        variant);
                assertThat(pendingHints(connection)).isEqualTo(1);
                assertThat(revision(connection, variant)).isEqualTo(1);
            } finally {
                connection.rollback();
            }

            assertThat(pendingHints(connection)).isZero();
            assertThat(stock(connection, variant)).isEqualTo(10);
            assertThat(revision(connection, variant)).isZero();
        }
    }

    @Test
    @Order(8)
    void disabledOrZeroBudgetCommitsTheStockWriteWithoutHintsOrAccounting() throws Exception {
        boolean[] enabledValues = {false, true};
        long[] budgets = {1000, 0};
        try (Connection connection = connect()) {
            for (int index = 0; index < enabledValues.length; index++) {
                long product = product(connection, "budget-product-" + index, "ACTIVE");
                long variant = variant(connection, "variant-budget-" + index, product, 10, false);
                execute(connection, "update public.app_realtime_control "
                                + "set enabled = ?, remaining_message_budget = ? where id = 1",
                        enabledValues[index], budgets[index]);

                execute(connection, "update public.variants set variant_stock = 6 where id = ?",
                        variant);

                assertThat(stock(connection, variant)).isEqualTo(6);
                assertThat(revision(connection, variant)).isEqualTo(1);
                assertThat(pendingHints(connection)).isZero();
                assertThat(count(connection,
                        "select count(*) from public.realtime_send_accounting")).isZero();
                assertThat(count(connection,
                        "select count(*) from public.realtime_token_leases")).isZero();
                assertThat(count(connection, """
                        select count(*) from public.app_realtime_control
                         where id = 1 and enabled = ? and remaining_message_budget = ?
                        """, enabledValues[index], budgets[index])).isEqualTo(1);
            }
        }
    }

    @Test
    @Order(9)
    void enqueueFailureIsSwallowedAndTheStockWriteStillAdvancesRevision() throws Exception {
        try (Connection connection = connect()) {
            long product = product(connection, "enqueue-failure-product", "ACTIVE");
            long variant = variant(connection, "variant-enqueue-failure", product, 10, false);

            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        alter function public.app_realtime_enqueue_signal(text, text, jsonb)
                            rename to app_realtime_enqueue_signal_task13_off
                        """);
                execute(connection, "update public.variants set variant_stock = 3 where id = ?",
                        variant);

                assertThat(stock(connection, variant)).isEqualTo(3);
                assertThat(revision(connection, variant)).isEqualTo(1);
                assertThat(pendingHints(connection)).isZero();
            } finally {
                connection.rollback();
            }

            assertThat(count(connection, """
                    select count(*) from pg_proc
                     where oid = 'public.app_realtime_enqueue_signal(text, text, jsonb)'::regprocedure
                    """)).isEqualTo(1);
            assertThat(stock(connection, variant)).isEqualTo(10);
            assertThat(revision(connection, variant)).isZero();
            assertThat(pendingHints(connection)).isZero();
        }
    }

    @Test
    @Order(10)
    void droppingAllOperatorTriggersKeepsV35RevisionIncrements() throws Exception {
        try (Connection connection = connect()) {
            long product = product(connection, "revision-independent-product", "ACTIVE");
            long variant = variant(connection, "variant-revision-independent", product, 10, false);

            connection.setAutoCommit(false);
            try {
                execute(connection, "drop trigger variants_stock_signal on public.variants");
                execute(connection, "drop trigger variants_visibility_signal on public.variants");
                execute(connection, "drop trigger products_visibility_signal on public.products");
                assertThat(operatorTriggerCount(connection)).isZero();

                execute(connection, "update public.variants set variant_stock = 1 where id = ?",
                        variant);

                assertThat(stock(connection, variant)).isEqualTo(1);
                assertThat(revision(connection, variant)).isEqualTo(1);
                assertThat(pendingHints(connection)).isZero();
                assertThat(count(connection, """
                        select count(*) from pg_trigger
                         where tgname = 'variants_stock_revision'
                           and tgrelid = 'public.variants'::regclass
                        """)).isEqualTo(1);
            } finally {
                connection.rollback();
            }

            assertThat(operatorTriggerCount(connection)).isEqualTo(3);
            assertThat(stock(connection, variant)).isEqualTo(10);
            assertThat(revision(connection, variant)).isZero();
        }
    }

    @Test
    @Order(11)
    void missingPrerequisitesFailClosedWithClearErrors() throws Exception {
        String[][] cases = {
                {"alter table public.realtime_signal_outbox rename to task13_missing_outbox",
                        "public.realtime_signal_outbox missing"},
                {"delete from public.app_realtime_control where id = 1",
                        "app_realtime_control row id=1 missing"},
                {"alter function public.app_realtime_enqueue_signal(text, text, jsonb) "
                        + "rename to task13_missing_enqueue",
                        "app_realtime_enqueue_signal(text,text,jsonb) missing"},
                {"alter table public.products rename to task13_missing_products",
                        "public.products missing"},
                {"alter table public.products rename column id to task13_missing_id",
                        "public.products.id missing"},
                {"alter table public.products rename column product_id to task13_missing_business_id",
                        "public.products.product_id missing"},
                {"alter table public.products rename column product_status to task13_missing_status",
                        "public.products.product_status missing"},
                {"alter table public.variants rename to task13_missing_variants",
                        "public.variants missing"},
                {"alter table public.variants rename column product_id to task13_missing_variant_product",
                        "public.variants.product_id missing"},
                {"alter table public.variants rename column variant_stock to task13_missing_stock",
                        "public.variants.variant_stock missing"},
                {"alter table public.variants rename column is_deleted to task13_missing_deleted",
                        "public.variants.is_deleted missing"},
                {"alter table public.variants rename column stock_revision to task13_missing_revision",
                        "public.variants.stock_revision missing"}
        };

        for (String[] missing : cases) {
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                try {
                    execute(connection, missing[0]);
                    assertThatThrownBy(() -> execute(connection, script))
                            .as(missing[1])
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("Inventory realtime prerequisite not found")
                            .hasMessageContaining(missing[1]);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    @Order(12)
    void triggerPathDoesNotLockControlCallRealtimeSendOrWriteLeasesAndAccounting() throws Exception {
        try (Connection main = connect(); Connection locker = connect()) {
            long product = product(main, "isolation-product", "ACTIVE");
            long variant = variant(main, "variant-isolation", product, 10, false);

            String controlBefore = string(main, """
                    select enabled || ':' || remaining_message_budget || ':' || updated_at
                      from public.app_realtime_control where id = 1
                    """);
            locker.setAutoCommit(false);
            execute(locker, "select id from public.app_realtime_control where id = 1 for update");

            main.setAutoCommit(false);
            try {
                execute(main, "set local lock_timeout = '500ms'");
                execute(main, """
                        create schema realtime;
                        create table public.realtime_test_capture (
                            topic text,
                            event text,
                            payload jsonb
                        );
                        create function realtime.send(
                                payload jsonb, event text, topic text, private boolean)
                        returns void language sql as $$
                            insert into public.realtime_test_capture (topic, event, payload)
                            values (topic, event, payload)
                        $$;
                        """);

                execute(main, "update public.variants set variant_stock = 8 where id = ?", variant);

                assertThat(stock(main, variant)).isEqualTo(8);
                assertThat(revision(main, variant)).isEqualTo(1);
                assertThat(pendingHints(main)).isEqualTo(1);
                assertThat(count(main,
                        "select count(*) from public.realtime_test_capture")).isZero();
                assertThat(count(main,
                        "select count(*) from public.realtime_send_accounting")).isZero();
                assertThat(count(main,
                        "select count(*) from public.realtime_token_leases")).isZero();
                assertThat(string(main, """
                        select enabled || ':' || remaining_message_budget || ':' || updated_at
                          from public.app_realtime_control where id = 1
                        """)).as("No control lock or budget update from the trigger path")
                        .isEqualTo(controlBefore);
            } finally {
                main.rollback();
                locker.rollback();
            }

            assertThat(count(main,
                    "select count(*) from pg_namespace where nspname = 'realtime'")).isZero();
            assertThat(pendingHints(main)).isZero();
            assertThat(stock(main, variant)).isEqualTo(10);
            assertThat(revision(main, variant)).isZero();
        }
    }

    @Test
    @Order(13)
    void outboxRowsCarryOnlyTheMinimalProductInvalidation() throws Exception {
        try (Connection connection = connect()) {
            long product = product(connection, "payload-safe-product", "ACTIVE");
            long variant = variant(connection, "variant-payload-safe", product, 10, false);

            execute(connection, "update public.variants set variant_stock = 5 where id = ?", variant);
            execute(connection, "update public.variants set is_deleted = true where id = ?", variant);
            execute(connection, "update public.products set product_status = 'DRAFT' where id = ?",
                    product);

            assertThat(pendingHints(connection)).isEqualTo(3);
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where (select count(*) from jsonb_object_keys(payload)) <> 1
                        or not jsonb_exists(payload, 'productId')
                        or payload - 'productId' <> '{}'::jsonb
                        or topic <> 'stock:' || (payload ->> 'productId')
                        or event_name <> 'stock.changed'
                        or octet_length(payload::text) > 1024
                    """)).isZero();
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox
                     where payload::text ~* '(customer|order|email|sku|revision|stock|variant|old|new)'
                    """)).as("Payload reveals no stock, revision, row, customer, or order data")
                    .isZero();
            assertThat(count(connection, """
                    select count(*) from public.realtime_signal_outbox h
                     where to_jsonb(h)::text ~* '(customer|email|sku|revision|order)'
                    """)).as("The complete outbox row carries no unrelated business data")
                    .isZero();
        }
    }

    private static void assertOperatorInstallShape(Connection connection) throws SQLException {
        assertThat(count(connection, """
                select count(*) from pg_proc p
                  join pg_namespace n on n.oid = p.pronamespace
                 where n.nspname = 'public'
                   and p.proname = 'enqueue_stock_signal'
                   and p.pronargs = 0
                   and p.prosecdef
                   and 'search_path=""' = any(p.proconfig)
                """)).as("One shared security-definer trigger function").isEqualTo(1);
        assertThat(count(connection, """
                select count(*) from pg_proc p
                 cross join lateral aclexplode(
                         coalesce(p.proacl, acldefault('f', p.proowner))) a
                 where p.oid = 'public.enqueue_stock_signal()'::regprocedure
                   and a.privilege_type = 'EXECUTE'
                   and a.grantee <> p.proowner
                """)).as("All direct EXECUTE grants are revoked").isZero();
        assertThat(count(connection, """
                select count(*) from pg_proc p
                 cross join lateral aclexplode(
                         coalesce(p.proacl, acldefault('f', p.proowner))) a
                 where p.oid = 'public.enqueue_stock_signal()'::regprocedure
                   and a.grantee = 0
                """)).as("PUBLIC cannot execute the trigger function").isZero();

        for (String trigger : TRIGGER_NAMES) {
            assertThat(count(connection, """
                    select count(*) from pg_trigger
                     where tgname = ? and not tgisinternal and tgfoid = ?
                    """, trigger, functionOid(connection))).as(trigger).isEqualTo(1);
        }
        assertThat(operatorTriggerCount(connection)).isEqualTo(TRIGGER_NAMES.size());
    }

    private static long functionOid(Connection connection) throws SQLException {
        return count(connection,
                "select oid from pg_proc where oid = 'public.enqueue_stock_signal()'::regprocedure");
    }

    private static long operatorTriggerCount(Connection connection) throws SQLException {
        return count(connection, """
                select count(*) from pg_trigger
                 where tgname in ('variants_stock_signal', 'variants_visibility_signal',
                                  'products_visibility_signal')
                   and not tgisinternal
                """);
    }

    private static long product(Connection connection, String businessId, String status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into public.products (product_id, product_name, product_description,
                                             product_slug, product_status)
                values (?, ?, ?, ?, ?)
                returning id
                """)) {
            statement.setString(1, businessId);
            statement.setString(2, "Task 13 fixture");
            statement.setString(3, "Task 13 scratch fixture");
            statement.setString(4, businessId);
            statement.setString(5, status);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static long variant(Connection connection, String variantId, long product,
                                int stock, boolean deleted) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into public.variants (variant_id, variant_sku, variant_price,
                                             variant_stock, product_id, is_deleted)
                values (?, ?, 10.00, ?, ?, ?)
                returning id
                """)) {
            statement.setString(1, variantId);
            statement.setString(2, variantId);
            statement.setInt(3, stock);
            statement.setLong(4, product);
            statement.setBoolean(5, deleted);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static long stock(Connection connection, long variant) throws SQLException {
        return count(connection,
                "select variant_stock from public.variants where id = ?", variant);
    }

    private static long revision(Connection connection, long variant) throws SQLException {
        return count(connection,
                "select stock_revision from public.variants where id = ?", variant);
    }

    private static long pendingHints(Connection connection) throws SQLException {
        return count(connection,
                "select count(*) from public.realtime_signal_outbox where sent_at is null");
    }

    private static String currentDatabase(Connection connection) throws SQLException {
        return string(connection, "select current_database()");
    }

    private static String string(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static long count(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        if (parameters.length == 0) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.execute();
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters)
            throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
    }
}
