package com.project.dacn;

import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.entity.cart.Cart;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.catalog.Media;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.mapper.cart.CartMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CartMapperTest {

    private final CartMapper mapper = Mappers.getMapper(CartMapper.class);

    @Test
    void itemResponseComputesLineTotal() {
        CartItem item = item(new BigDecimal("10.00"), 2, 5);
        CartItemResponse res = mapper.toCartItemResponse(item);
        assertThat(res.lineTotal()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(res.price()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(res.productName()).isEqualTo("P");
        assertThat(res.stock()).isEqualTo(5);
    }

    @Test
    void detailSumsTotalsNullSafe() {
        CartItemResponse a = new CartItemResponse(1L, "c", 1L, "S1", 2,
                new BigDecimal("10.00"), new BigDecimal("20.00"), "p", "P", null, 5, null, null);
        CartItemResponse b = new CartItemResponse(2L, "c", 2L, "S2", 1,
                null, null, "p", "P", null, 3, null, null);
        Cart cart = Cart.builder().customer(Customer.builder().name("C").build()).build();
        CartDetailResponse detail = mapper.toDetail(cart, List.of(a, b));
        assertThat(detail.totalQuantity()).isEqualTo(3);
        assertThat(detail.totalAmount()).isEqualByComparingTo(new BigDecimal("20.00"));
    }

    private CartItem item(BigDecimal price, int qty, int stock) {
        Product product = Product.builder().name("P").description("d")
                .thumbnail(Media.builder().url("t").build()).build();
        Variant variant = Variant.builder().product(product).sku("S1").price(price).stock(stock).build();
        Cart cart = Cart.builder().build();
        return CartItem.builder().cart(cart).variant(variant).quantity(qty).build();
    }
}
