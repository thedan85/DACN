package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.event.OrderEventType;
import com.project.dacn.integration.mail.MailContentProperties;
import com.project.dacn.service.engagement.MailViewBuilder.MailView;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Properties;

import static com.project.dacn.common.constant.PaymentMethod.COD;
import static com.project.dacn.common.constant.PaymentMethod.VIETQR;
import static com.project.dacn.event.OrderEventType.ORDER_COMPLETED;
import static com.project.dacn.event.OrderEventType.ORDER_CONFIRMED;
import static com.project.dacn.event.OrderEventType.ORDER_EXPIRED;
import static com.project.dacn.event.OrderEventType.ORDER_PLACED;
import static com.project.dacn.event.OrderEventType.ORDER_REFUNDED;
import static com.project.dacn.event.OrderEventType.ORDER_REJECTED;
import static com.project.dacn.event.OrderEventType.ORDER_SHIPPED;
import static com.project.dacn.event.OrderEventType.PAYMENT_FAILED;
import static com.project.dacn.event.OrderEventType.REFUND_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

/** Pure render tests: truthful copy matrix, escaped display data, deterministic Message-ID. */
class MailViewBuilderTest {

    private static final MailContentProperties CONFIG =
            new MailContentProperties("http://localhost:3000", "no-reply@notifications.local",
                    "mail.example.test");

    private MailViewBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new MailViewBuilder(CONFIG);
    }

    @Test
    void pendingReceiptCopyNeverClaimsPaid() {
        MailView online = builder.view(event(ORDER_PLACED, VIETQR, PaymentStatus.PENDING, "ORD_1"), 1L, 1);
        assertThat(online.htmlBody()).contains("Payment is pending").doesNotContain("Payment received");
        assertThat(online.htmlBody()).doesNotContain("paid");

        MailView cod = builder.view(event(ORDER_PLACED, COD, PaymentStatus.PENDING, "ORD_2"), 1L, 1);
        assertThat(cod.htmlBody()).contains("pending approval").contains("payment is due on delivery");
        assertThat(cod.htmlBody()).doesNotContain("Payment received");
    }

    @Test
    void confirmationDistinguishesPaidOnlineFromCodDue() {
        MailView paid = builder.view(event(ORDER_CONFIRMED, VIETQR, PaymentStatus.CONFIRMED, "ORD_3"), 1L, 1);
        assertThat(paid.htmlBody()).contains("Payment received");

        MailView cod = builder.view(event(ORDER_CONFIRMED, COD, PaymentStatus.PENDING, "ORD_4"), 1L, 1);
        assertThat(cod.htmlBody()).contains("Payment is due on delivery").doesNotContain("Payment received");
    }

    @Test
    void confirmationCopyFollowsPaymentMethodNotAnomalousPaymentStatus() {
        // COD confirmation must never claim payment received, even if an
        // upstream producer attaches a CONFIRMED payment status to it.
        MailView codConfirmed = builder.view(
                event(ORDER_CONFIRMED, COD, PaymentStatus.CONFIRMED, "ORD_cod_ anomaly"), 1L, 1);
        assertThat(codConfirmed.htmlBody()).contains("Payment is due on delivery")
                .doesNotContain("Payment received");

        MailView onlinePaid = builder.view(
                event(ORDER_CONFIRMED, VIETQR, PaymentStatus.CONFIRMED, "ORD_online"), 1L, 1);
        assertThat(onlinePaid.htmlBody()).contains("Payment received")
                .doesNotContain("Payment is due on delivery");
    }

    @Test
    void noFalseRefundedOrShippedClaims() {
        assertThat(builder.view(event(REFUND_REQUIRED, VIETQR, PaymentStatus.REFUND_REQUIRED, "ORD_5"), 1L, 1)
                .htmlBody()).contains("refund is under review").doesNotContain("refund confirmed");
        assertThat(builder.view(event(ORDER_COMPLETED, VIETQR, PaymentStatus.CONFIRMED, "ORD_6"), 1L, 1)
                .htmlBody()).contains("completed")
                .doesNotContain("shipped").doesNotContain("delivered");
        assertThat(builder.view(event(ORDER_REJECTED, VIETQR, PaymentStatus.PENDING, "ORD_7"), 1L, 1)
                .htmlBody()).contains("rejected").doesNotContain("refunded");
        assertThat(builder.view(event(ORDER_EXPIRED, VIETQR, PaymentStatus.PENDING, "ORD_8"), 1L, 1)
                .htmlBody()).contains("cancelled");
        assertThat(builder.view(event(PAYMENT_FAILED, VIETQR, PaymentStatus.FAILED, "ORD_9"), 1L, 1)
                .htmlBody()).contains("cancelled");
        // Only a genuine integration event may claim these outcomes.
        assertThat(builder.view(event(ORDER_SHIPPED, VIETQR, PaymentStatus.CONFIRMED, "ORD_10"), 1L, 1)
                .htmlBody()).contains("shipment confirmed");
        assertThat(builder.view(event(ORDER_REFUNDED, VIETQR, PaymentStatus.REFUNDED, "ORD_11"), 1L, 1)
                .htmlBody()).contains("refund confirmed");
    }

    @Test
    void loopbackBaseUrlsOmitOrderLink() {
        for (String base : new String[]{"http://localhost:3000", "http://127.0.0.1:3000",
                "http://[::1]:3000"}) {
            MailViewBuilder local = new MailViewBuilder(new MailContentProperties(
                    base, "no-reply@notifications.local", "mail.example.test"));
            MailView view = local.view(event(ORDER_PLACED, VIETQR, PaymentStatus.PENDING, "ORD_LOCAL"), 1L, 1);
            assertThat(view.textBody()).doesNotContain("View your order")
                    .doesNotContain("account/orders").doesNotContain("localhost");
            assertThat(view.htmlBody()).doesNotContain("account/orders")
                    .doesNotContain("<a ");
        }
    }

    @Test
    void publicHttpsBaseUrlKeepsOrderLink() {
        MailViewBuilder publicBuilder = new MailViewBuilder(new MailContentProperties(
                "https://shop.example.test", "no-reply@notifications.local", "mail.example.test"));
        MailView view = publicBuilder.view(
                event(ORDER_PLACED, VIETQR, PaymentStatus.PENDING, "ORD_PUBLIC"), 1L, 1);
        assertThat(view.textBody())
                .contains("View your order: https://shop.example.test/account/orders/ORD_PUBLIC");
        assertThat(view.htmlBody())
                .contains("<a href=\"https://shop.example.test/account/orders/ORD_PUBLIC\"");
    }

    @Test
    void escapesUserControlledOrderIdInHtml() {
        MailView view = builder.view(
                event(ORDER_PLACED, VIETQR, PaymentStatus.PENDING, "ORD_<b onclick='x'>bad</b>"), 1L, 1);
        assertThat(view.htmlBody()).doesNotContain("<b onclick").doesNotContain("<script");
        assertThat(view.htmlBody()).contains("&lt;b").contains("&gt;");
        // The text alternative carries no active markup and no HTML entities for the payload.
        assertThat(view.textBody()).contains("ORD_").doesNotContain("&lt;b");
    }

    @Test
    void deterministicMessageIdHasNoRecipient() throws Exception {
        OrderEvent event = event(ORDER_PLACED, VIETQR, PaymentStatus.PENDING, "ORD_1");
        MailView view = builder.view(event, 42L, 2);

        MimeMessage first = builder.createMessage(Session.getInstance(new Properties()), view, "victim@example.invalid");
        MimeMessage second = builder.createMessage(Session.getInstance(new Properties()), view, "victim@example.invalid");
        first.saveChanges();
        second.saveChanges();

        assertThat(first.getMessageID()).isNotBlank().isEqualTo(second.getMessageID());
        assertThat(first.getMessageID()).startsWith("<").endsWith(">")
                .contains(event.eventId().toString()).contains("42").contains("2")
                .contains("mail.example.test")
                .doesNotContain("victim").doesNotContain("example.invalid");
        assertThat(((InternetAddress) first.getFrom()[0]).getAddress())
                .isEqualTo("no-reply@notifications.local");
        assertThat(first.getRecipients(Message.RecipientType.TO)).hasSize(1);
        assertThat(((InternetAddress) first.getRecipients(Message.RecipientType.TO)[0]).getAddress())
                .isEqualTo("victim@example.invalid");
        System.out.println("TASK10_EVIDENCE messageId=" + first.getMessageID()
                + " deterministic=" + first.getMessageID().equals(second.getMessageID())
                + " recipientInId=" + first.getMessageID().contains("victim"));
    }

    private static OrderEvent event(OrderEventType type, PaymentMethod method,
                                    PaymentStatus paymentStatus, String orderId) {
        return OrderEvent.create(type, orderId, 1L, Instant.now().minusSeconds(30), 7L,
                OrderStatus.PENDING, paymentStatus, method, new BigDecimal("12.34"),
                reasonFor(type), "unit-corr", "TEST");
    }

    private static OrderEventReason reasonFor(OrderEventType type) {
        return switch (type) {
            case ORDER_REJECTED -> OrderEventReason.STAFF_REJECTED;
            case ORDER_EXPIRED -> OrderEventReason.PAYMENT_EXPIRED;
            case PAYMENT_FAILED -> OrderEventReason.PAYMENT_FAILED;
            default -> null;
        };
    }
}
