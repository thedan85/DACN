package com.project.dacn.modules.cart;

import com.project.dacn.modules.cart.dto.request.CartItemCreateRequest;
import com.project.dacn.modules.cart.entity.CartItem;
import com.project.dacn.modules.cart.mapper.CartMapper;
import com.project.dacn.modules.engagement.dto.request.ReviewCreateRequest;
import com.project.dacn.modules.engagement.entity.Review;
import com.project.dacn.modules.engagement.mapper.EngagementMapper;
import com.project.dacn.modules.order.dto.request.OrderCreateRequest;
import com.project.dacn.modules.order.entity.Order;
import com.project.dacn.modules.order.mapper.OrderMapper;
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
        assertThat(order.getStatus()).isEqualTo(com.project.dacn.modules.common.enums.OrderStatus.PENDING);
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
