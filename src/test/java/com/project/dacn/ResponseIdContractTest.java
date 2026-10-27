package com.project.dacn;

import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.dto.response.cart.CartResponse;
import com.project.dacn.dto.response.catalog.VariantResponse;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseIdContractTest {

    @Test
    void cartResponseIdsAreBusinessIds() {
        assertType(CartDetailResponse.class, "customerId", String.class);
        assertType(CartResponse.class, "customerId", String.class);
        assertType(CartItemResponse.class, "cartItemId", String.class);
        assertType(CartItemResponse.class, "variantId", String.class);
    }

    @Test
    void orderResponseIdsAreBusinessIds() {
        assertType(OrderResponse.class, "customerId", String.class);
        assertType(OrderItemResponse.class, "variantId", String.class);
        assertThat(componentNames(OrderItemResponse.class)).doesNotContain("orderItemId");
    }

    @Test
    void variantResponseExposesOnlyBusinessId() {
        assertThat(componentNames(VariantResponse.class)).doesNotContain("id").contains("variantId");
    }

    @Test
    void reviewResponseDoesNotExposeOrderItemDatabaseId() {
        assertThat(componentNames(ReviewResponse.class)).doesNotContain("orderItemId");
    }

    private static void assertType(Class<? extends Record> type, String name, Class<?> expected) {
        assertThat(component(type, name).getType()).isEqualTo(expected);
    }

    private static RecordComponent component(Class<? extends Record> type, String name) {
        return Arrays.stream(type.getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " missing from " + type.getSimpleName()));
    }

    private static java.util.List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
