package com.project.dacn.mapper.cart;

import com.project.dacn.dto.request.cart.CartCreateRequest;
import com.project.dacn.dto.request.cart.CartItemCreateRequest;
import com.project.dacn.dto.request.cart.CartItemUpdateRequest;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.dto.response.cart.CartResponse;
import com.project.dacn.entity.cart.Cart;
import com.project.dacn.entity.cart.CartItem;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.math.BigDecimal;
import java.util.List;

@Mapper(componentModel = "spring")
public interface CartMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "cartId", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Cart toCart(CartCreateRequest request);

    @Mapping(source = "customer.customerId", target = "customerId")
    @Mapping(source = "customer.name", target = "customerName")
    CartResponse toCartResponse(Cart cart);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "cartItemId", ignore = true)
    @Mapping(target = "cart", ignore = true)
    @Mapping(target = "variant", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    CartItem toCartItem(CartItemCreateRequest request);

    @Mapping(source = "cartItemId", target = "cartItemId")
    @Mapping(source = "cart.cartId", target = "cartId")
    @Mapping(source = "variant.variantId", target = "variantId")
    @Mapping(source = "variant.sku", target = "sku")
    @Mapping(source = "variant.price", target = "price")
    @Mapping(source = "variant.product.productId", target = "productId")
    @Mapping(source = "variant.product.name", target = "productName")
    @Mapping(source = "variant.product.thumbnail.url", target = "thumbnail")
    @Mapping(source = "variant.stock", target = "stock")
    @Mapping(target = "lineTotal", source = ".", qualifiedByName = "toLineTotal")
    CartItemResponse toCartItemResponse(CartItem item);

    @Named("toLineTotal")
    default BigDecimal toLineTotal(CartItem item) {
        if (item == null || item.getVariant() == null
                || item.getVariant().getPrice() == null || item.getQuantity() == null) {
            return null;
        }
        return item.getVariant().getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
    }

    default CartDetailResponse toDetail(Cart cart, List<CartItemResponse> items) {
        List<CartItemResponse> safeItems = items == null ? List.of() : items;
        int totalQuantity = safeItems.stream()
                .mapToInt(i -> i.quantity() == null ? 0 : i.quantity())
                .sum();
        BigDecimal totalAmount = safeItems.stream()
                .map(CartItemResponse::lineTotal)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String customerId = cart != null && cart.getCustomer() != null
                ? cart.getCustomer().getCustomerId()
                : null;
        String customerName = cart != null && cart.getCustomer() != null
                ? cart.getCustomer().getName()
                : null;
        return new CartDetailResponse(
                cart == null ? null : cart.getCartId(),
                customerId,
                customerName,
                safeItems,
                totalQuantity,
                totalAmount,
                cart == null ? null : cart.getCreatedAt(),
                cart == null ? null : cart.getUpdatedAt());
    }

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "cartItemId", ignore = true)
    @Mapping(target = "cart", ignore = true)
    @Mapping(target = "variant", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateCartItem(CartItemUpdateRequest request, @MappingTarget CartItem item);
}
