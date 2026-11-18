package com.project.dacn.service.engagement;

import com.project.dacn.event.OrderEvent;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.integration.mail.MailContentProperties;
import com.project.dacn.integration.mail.SmtpAttemptExecutor;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.stereotype.Component;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Renders escaped, truthful mail from immutable event facts and builds the
 * attempt-owned MimeMessage. All display text is passed to Thymeleaf via
 * th:text, so user-controlled order data is escaped at render time. The
 * deterministic Message-ID overrides updateMessageID to avoid the stock
 * reverse-DNS lookup that would consume the attempt budget.
 */
@Component
public class MailViewBuilder {

    public record MailView(String subject, String textBody, String htmlBody, String messageId) {
    }

    private static final String TEMPLATE = "order-notification";

    private final MailContentProperties properties;
    private final SpringTemplateEngine htmlEngine;
    private final SpringTemplateEngine textEngine;

    public MailViewBuilder(MailContentProperties properties) {
        this.properties = properties;
        this.htmlEngine = engine(TemplateMode.HTML, ".html");
        this.textEngine = engine(TemplateMode.TEXT, ".txt");
    }

    public MailView view(OrderEvent event, long deliveryId, int attempt) {
        String subject = "DACN: " + subjectLabel(event);
        String message = message(event);
        String base = properties.storefrontBaseUrl();
        // A loopback storefront is unreachable for recipients; omit the link rather
        // than ship a dead URL that hurts deliverability.
        String orderLink = isLocalBaseUrl(base) ? ""
                : base + "/account/orders/" + URLEncoder.encode(event.orderId(), StandardCharsets.UTF_8);
        String footer = "DACN storefront";

        // TEXT mode has no element processors, so the whole link block (label,
        // URL and trailing blank line) is passed ready to render or empty.
        String orderLinkBlock = orderLink.isEmpty() ? ""
                : "View your order: " + orderLink + "\n\n";

        Context context = new Context();
        context.setVariable("subject", subject);
        context.setVariable("message", message);
        context.setVariable("orderLink", orderLink);
        context.setVariable("orderLinkBlock", orderLinkBlock);
        context.setVariable("footer", footer);

        String messageId = "<order-notif-" + event.eventId() + "-" + deliveryId + "-" + attempt
                + "@" + properties.messageIdDomain() + ">";
        return new MailView(subject, textEngine.process(TEMPLATE, context),
                htmlEngine.process(TEMPLATE, context), messageId);
    }

    /** True for a loopback/local storefront host, whose links no recipient can open. */
    private static boolean isLocalBaseUrl(String base) {
        try {
            String host = new URI(base).getHost();
            if (host == null) {
                return false;
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public MimeMessage createMessage(Session session, MailView view, String recipientEmail)
            throws MessagingException {
        MimeMessage message = new MimeMessage(session) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", view.messageId());
            }
        };
        message.setFrom(new InternetAddress(properties.fromAddress(), false));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO,
                new InternetAddress[]{new InternetAddress(recipientEmail, false)});
        message.setSubject(view.subject(), StandardCharsets.UTF_8.name());

        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(view.textBody(), StandardCharsets.UTF_8.name());
        MimeBodyPart html = new MimeBodyPart();
        html.setContent(view.htmlBody(), "text/html; charset=" + StandardCharsets.UTF_8.name());
        alternative.addBodyPart(text);
        alternative.addBodyPart(html);
        message.setContent(alternative);
        return message;
    }

    public SmtpAttemptExecutor.MessageFactory factory(
            OrderEvent event, long deliveryId, int attempt, String recipientEmail) {
        MailView view = view(event, deliveryId, attempt);
        return session -> createMessage(session, view, recipientEmail);
    }

    /** Copy is derived only from immutable event facts; values are raw here and escaped by the template. */
    private static String message(OrderEvent event) {
        String order = event.orderId();
        String total = event.total();
        return switch (event.eventType()) {
            case ORDER_PLACED -> event.paymentMethod() == com.project.dacn.common.constant.PaymentMethod.COD
                    ? "Order " + order + " received. Your order is pending approval; "
                            + "payment is due on delivery. Total: " + total + "."
                    : "Order " + order + " received. Payment is pending. Total: " + total + ".";
            // Copy follows payment method, not a possibly anomalous payment
            // status: a COD confirmation never claims payment was received.
            case ORDER_CONFIRMED -> event.paymentMethod() == com.project.dacn.common.constant.PaymentMethod.COD
                    ? "Order " + order + " confirmed. Payment is due on delivery. Total: " + total + "."
                    : "Order " + order + " confirmed. Payment received. Total: " + total + ".";
            case ORDER_REJECTED -> "Order " + order + " was rejected" + reasonSuffix(event.reason()) + ".";
            case ORDER_EXPIRED -> "Order " + order + " was cancelled because the payment window expired.";
            case PAYMENT_FAILED -> "Order " + order + " was cancelled because payment failed.";
            case REFUND_REQUIRED -> "Order " + order
                    + ": a refund is under review. We will contact you when the review completes.";
            case ORDER_COMPLETED -> "Order " + order + " was completed. Thank you for shopping with us.";
            case ORDER_SHIPPED -> "Order " + order + " shipment confirmed.";
            case ORDER_REFUNDED -> "Order " + order + " refund confirmed.";
        };
    }

    private static String reasonSuffix(OrderEventReason reason) {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case STAFF_REJECTED -> " by staff";
            case PAYMENT_EXPIRED -> " after the payment window expired";
            case PAYMENT_FAILED -> " after payment failed";
        };
    }

    /** Fixed, user-data-free subject: no recipient-supplied value can inject a header. */
    private static String subjectLabel(OrderEvent event) {
        return switch (event.eventType()) {
            case ORDER_PLACED -> "Order received";
            case ORDER_CONFIRMED -> "Order confirmed";
            case ORDER_REJECTED, ORDER_EXPIRED, PAYMENT_FAILED -> "Order status update";
            case REFUND_REQUIRED -> "Refund review update";
            case ORDER_COMPLETED -> "Order completed";
            case ORDER_SHIPPED -> "Shipment update";
            case ORDER_REFUNDED -> "Refund update";
        };
    }

    private static SpringTemplateEngine engine(TemplateMode mode, String suffix) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/mail/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCheckExistence(true);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
