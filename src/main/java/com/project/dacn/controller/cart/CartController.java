package com.project.dacn.controller.cart;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.dto.request.cart.CartItemAddRequest;
import com.project.dacn.dto.request.cart.CartItemUpdateRequest;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
@Tag(name = "cart")
public class CartController {

    private final CartService service;

    @GetMapping
    public ApiResponse<CartDetailResponse> get(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            Authentication auth) {
        return ApiResponse.ok(service.getDetail(resolveCustomerId(auth, customerId)));
    }

    @DeleteMapping
    public ApiResponse<Void> clear(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            Authentication auth) {
        service.clear(resolveCustomerId(auth, customerId));
        return ApiResponse.ok(null);
    }

    @PostMapping("/items")
    public ApiResponse<CartItemResponse> add(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            Authentication auth,
            @Valid @RequestBody CartItemAddRequest request) {
        return ApiResponse.ok(service.addItem(resolveCustomerId(auth, customerId), request));
    }

    @PatchMapping("/items/{cartItemId}")
    public ApiResponse<CartItemResponse> patch(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            Authentication auth,
            @PathVariable Long cartItemId,
            @Valid @RequestBody CartItemUpdateRequest request) {
        return ApiResponse.ok(service.setQuantity(resolveCustomerId(auth, customerId), cartItemId, request));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ApiResponse<Void> remove(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            Authentication auth,
            @PathVariable Long cartItemId) {
        service.removeItem(resolveCustomerId(auth, customerId), cartItemId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/checkout")
    public ApiResponse<OrderResponse> checkout(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            Authentication auth,
            @Valid @RequestBody CartCheckoutRequest request) {
        return ApiResponse.ok(service.checkout(resolveCustomerId(auth, customerId), request));
    }

    private Long resolveCustomerId(Authentication auth, Long header) {
        // TODO: JWT — resolve the customer DB id from the authenticated principal via JwtService once it lands.
        if (auth != null && auth.getName() != null && auth.getName().matches("\\d+")) {
            return Long.valueOf(auth.getName());
        }
        return header;
    }
}
