package com.project.dacn.controller.cart;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.dto.request.cart.CartItemAddRequest;
import com.project.dacn.dto.request.cart.CartItemUpdateRequest;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.security.CustomerIdentity;
import com.project.dacn.service.cart.CartService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
@Tag(name = "cart")
public class CartController {

    private final CartService service;
    private final CustomerIdentity identity;

    @GetMapping
    public ApiResponse<CartDetailResponse> get(Authentication auth) {
        return ApiResponse.ok(service.getDetail(identity.requireCustomerId(auth)));
    }

    @DeleteMapping
    public ApiResponse<Void> clear(Authentication auth) {
        service.clear(identity.requireCustomerId(auth));
        return ApiResponse.ok(null);
    }

    @PostMapping("/items")
    public ApiResponse<CartItemResponse> add(
            Authentication auth,
            @Valid @RequestBody CartItemAddRequest request) {
        return ApiResponse.ok(service.addItem(identity.requireCustomerId(auth), request));
    }

    @PatchMapping("/items/{cartItemId}")
    public ApiResponse<CartItemResponse> patch(
            Authentication auth,
            @PathVariable String cartItemId,
            @Valid @RequestBody CartItemUpdateRequest request) {
        return ApiResponse.ok(service.setQuantity(
                identity.requireCustomerId(auth), cartItemId, request));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ApiResponse<Void> remove(Authentication auth, @PathVariable String cartItemId) {
        service.removeItem(identity.requireCustomerId(auth), cartItemId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/checkout")
    public ApiResponse<OrderResponse> checkout(
            Authentication auth,
            @Valid @RequestBody CartCheckoutRequest request) {
        return ApiResponse.ok(service.checkout(identity.requireCustomerId(auth), request));
    }
}
