package com.project.dacn.modules.cart.mapper;

import com.project.dacn.modules.cart.dto.request.CartCreateRequest;
import com.project.dacn.modules.cart.dto.request.CartItemCreateRequest;
import com.project.dacn.modules.cart.dto.request.CartItemUpdateRequest;
import com.project.dacn.modules.cart.dto.response.CartItemResponse;
import com.project.dacn.modules.cart.dto.response.CartResponse;
import com.project.dacn.modules.cart.entity.Cart;
import com.project.dacn.modules.cart.entity.CartItem;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

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

    @Mapping(source = "cart.cartId", target = "cartId")
    @Mapping(source = "variant.variantId", target = "variantId")
    @Mapping(source = "variant.sku", target = "sku")
    CartItemResponse toCartItemResponse(CartItem item);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "cartItemId", ignore = true)
    @Mapping(target = "cart", ignore = true)
    @Mapping(target = "variant", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateCartItem(CartItemUpdateRequest request, @MappingTarget CartItem item);
}
