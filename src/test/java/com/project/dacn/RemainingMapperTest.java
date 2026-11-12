package com.project.dacn;

import com.project.dacn.dto.request.cart.CartItemCreateRequest;
import com.project.dacn.common.constant.PaymentMethod;
import com.project.dacn.common.constant.PaymentStatus;
import com.project.dacn.dto.response.payment.PaymentResponse;
import com.project.dacn.event.OrderEventReason;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.mapper.cart.CartMapper;
import com.project.dacn.dto.request.engagement.ReviewCreateRequest;
import com.project.dacn.entity.engagement.Review;
import com.project.dacn.mapper.engagement.EngagementMapper;
import com.project.dacn.dto.request.order.OrderCreateRequest;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.mapper.order.OrderMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemainingMapperTest {

    private final CartMapper cartMapper = Mappers.getMapper(CartMapper.class);
    private final OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);
    private final EngagementMapper engagementMapper = Mappers.getMapper(EngagementMapper.class);

    @Test
    void cartItemCreateIgnoresIdsAndAssociations() {
        CartItem item = cartMapper.toCartItem(new CartItemCreateRequest("c-1", "v-1", 2));
        assertThat(item.getCartItemId()).isNull();
        assertThat(item.getCart()).isNull();
        assertThat(item.getVariant()).isNull();
        assertThat(item.getQuantity()).isEqualTo(2);
    }

    @Test
    void orderCreateDefaultsPendingAndIgnoresCustomer() {
        Order order = orderMapper.toOrder(new OrderCreateRequest("cust-1",
                new BigDecimal("10.00"), "addr", "Name", "090"));
        assertThat(order.getOrderId()).isNull();
        assertThat(order.getCustomer()).isNull();
        assertThat(order.getStatus()).isEqualTo(com.project.dacn.common.constant.OrderStatus.PENDING);
    }

    @Test
    void orderReadOverloadsPreserveReservationFactsAndExistingFields() {
        Instant deadline = Instant.parse("2026-11-12T12:15:00Z");
        Instant created = Instant.parse("2026-11-12T12:00:00Z");
        Order order = Order.builder().orderId("ORD_1")
                .customer(Customer.builder().customerId("CUS_1").name("An").build())
                .total(new BigDecimal("10.00")).address("addr").recipientName("An").phone("090")
                .createdAt(created).updatedAt(created).paymentExpiresAt(deadline)
                .cancellationReason(OrderEventReason.PAYMENT_EXPIRED).stockReleasedAt(deadline).build();
        PaymentResponse payment = new PaymentResponse("PAY_1", "ORD_1", PaymentMethod.BANK_TRANSFER,
                PaymentStatus.PENDING, new BigDecimal("10.00"), null, null, created, created);

        var base = orderMapper.toOrderResponse(order);
        var withItems = orderMapper.toOrderResponse(order, List.of());
        var enriched = orderMapper.toOrderResponse(order, null, payment);
        assertThat(List.of(base, withItems, enriched)).allSatisfy(response -> {
            assertThat(response.orderId()).isEqualTo("ORD_1");
            assertThat(response.customerId()).isEqualTo("CUS_1");
            assertThat(response.customerName()).isEqualTo("An");
            assertThat(response.status()).isEqualTo(com.project.dacn.common.constant.OrderStatus.PENDING);
            assertThat(response.total()).isEqualByComparingTo("10.00");
            assertThat(response.address()).isEqualTo("addr");
            assertThat(response.recipientName()).isEqualTo("An");
            assertThat(response.phone()).isEqualTo("090");
            assertThat(response.createdAt()).isEqualTo(created);
            assertThat(response.updatedAt()).isEqualTo(created);
            assertThat(response.paymentExpiresAt()).isEqualTo(deadline);
            assertThat(response.cancellationReason()).isEqualTo(OrderEventReason.PAYMENT_EXPIRED);
        });
        assertThat(withItems.items()).isEmpty();
        assertThat(enriched.items()).isEmpty();
        assertThat(enriched.payment()).isSameAs(payment);
        var json = new tools.jackson.databind.ObjectMapper().valueToTree(enriched);
        assertThat(json.get("paymentExpiresAt").asText()).isEqualTo("2026-11-12T12:15:00Z");
        assertThat(json.get("cancellationReason").asText()).isEqualTo("PAYMENT_EXPIRED");
        assertThat(json.has("stockReleasedAt")).isFalse();
    }

    @Test
    void reviewResponseFlattensIds() {
        Review review = Review.builder().rating(5).comment("ok").build();
        review.setReviewId("r-1");
        assertThat(engagementMapper.toReviewResponse(review).reviewId()).isEqualTo("r-1");
        Review mapped = engagementMapper.toReview(
                new ReviewCreateRequest(4, "good"));
        assertThat(mapped.getReviewId()).isNull();
        assertThat(mapped.getRating()).isEqualTo(4);
    }

    @Test
    void reviewResponseShowsCustomerNameAndOnlyStaffSeeCustomerId() {
        Customer customer = Customer.builder().customerId("CUS_1").name("An").phone("090").build();
        Review review = Review.builder().rating(5).customer(customer).build();

        ReviewResponse storefront = engagementMapper.toReviewResponse(review);
        assertThat(storefront.customerId()).isNull();
        assertThat(storefront.customerName()).isEqualTo("An");

        ReviewResponse staff = engagementMapper.toStaffReviewResponse(review);
        assertThat(staff.customerId()).isEqualTo("CUS_1");
        assertThat(staff.customerName()).isEqualTo("An");
    }

    @Test
    void reviewResponseMarksOnlyReviewsWithAnOrderItemAsVerified() {
        Review review = Review.builder().rating(5).build();
        assertThat(engagementMapper.toReviewResponse(review).verifiedPurchase()).isFalse();

        review.setOrderItem(OrderItem.builder().build());
        assertThat(engagementMapper.toReviewResponse(review).verifiedPurchase()).isTrue();
    }
}
