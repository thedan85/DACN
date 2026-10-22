package com.project.dacn.controller.cart;

import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.dto.request.cart.CartItemAddRequest;
import com.project.dacn.dto.request.cart.CartItemUpdateRequest;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.service.auth.CurrentCustomerService;
import com.project.dacn.service.cart.CartService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
    private final CurrentCustomerService currentCustomer;

    @GetMapping
    public ApiResponse<CartDetailResponse> get() {
        return ApiResponse.ok(service.getDetail(currentCustomer.requireCustomerId()));
    }

    @DeleteMapping
    public ApiResponse<Void> clear() {
        service.clear(currentCustomer.requireCustomerId());
        return ApiResponse.ok(null);
    }

    @PostMapping("/items")
    public ApiResponse<CartItemResponse> add(@Valid @RequestBody CartItemAddRequest request) {
        return ApiResponse.ok(service.addItem(currentCustomer.requireCustomerId(), request));
    }

    @PatchMapping("/items/{cartItemId}")
    public ApiResponse<CartItemResponse> patch(
            @PathVariable Long cartItemId,
            @Valid @RequestBody CartItemUpdateRequest request) {
        return ApiResponse.ok(service.setQuantity(currentCustomer.requireCustomerId(), cartItemId, request));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ApiResponse<Void> remove(@PathVariable Long cartItemId) {
        service.removeItem(currentCustomer.requireCustomerId(), cartItemId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/checkout")
    public ApiResponse<OrderResponse> checkout(@Valid @RequestBody CartCheckoutRequest request) {
        return ApiResponse.ok(service.checkout(currentCustomer.requireCustomerId(), request));
    }
}
