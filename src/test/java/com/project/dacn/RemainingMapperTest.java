package com.project.dacn;

import com.project.dacn.dto.request.cart.CartItemCreateRequest;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.mapper.cart.CartMapper;
import com.project.dacn.dto.request.engagement.ReviewCreateRequest;
import com.project.dacn.entity.engagement.Review;
import com.project.dacn.mapper.engagement.EngagementMapper;
import com.project.dacn.dto.request.order.OrderCreateRequest;
import com.project.dacn.entity.order.Order;
import com.project.dacn.mapper.order.OrderMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;

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
    void reviewResponseFlattensIds() {
        Review review = Review.builder().rating(5).comment("ok").build();
        review.setReviewId("r-1");
        assertThat(engagementMapper.toReviewResponse(review).reviewId()).isEqualTo("r-1");
        Review mapped = engagementMapper.toReview(
                new ReviewCreateRequest("v-1", "c-1", null, 4, "good"));
        assertThat(mapped.getReviewId()).isNull();
        assertThat(mapped.getRating()).isEqualTo(4);
    }
}
