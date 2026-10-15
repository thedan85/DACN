package com.project.dacn.service.cart;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.OutOfStockException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.cart.CartItemAddRequest;
import com.project.dacn.dto.request.cart.CartItemUpdateRequest;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.entity.cart.Cart;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.mapper.cart.CartMapper;
import com.project.dacn.repository.cart.CartItemRepository;
import com.project.dacn.repository.cart.CartRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class CartService {

    private final CustomerRepository customerRepository;
    private final VariantRepository variantRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CartMapper mapper;

    public CartDetailResponse getDetail(String customerId) {
        Cart cart = getOrCreate(customerId);
        List<CartItem> items = cartItemRepository.findWithDetailsByCart_CartId(cart.getCartId());
        List<CartItemResponse> responses = items.stream().map(mapper::toCartItemResponse).toList();
        return mapper.toDetail(cart, responses);
    }

    public CartItemResponse addItem(String customerId, CartItemAddRequest req) {
        if (req == null || req.quantity() == null || req.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        Cart cart = getOrCreate(customerId);
        Variant variant = requireSellable(req.variantId());
        int quantity = req.quantity();
        Optional<CartItem> existing =
                cartItemRepository.findByCart_CartIdAndVariant_VariantId(cart.getCartId(), variant.getVariantId());
        if (existing.isPresent()) {
            CartItem item = existing.get();
            log.debug("Bumping cart item {} from {} by {}", item.getCartItemId(), item.getQuantity(), quantity);
            return mapper.toCartItemResponse(saveWithCap(item, item.getQuantity() + quantity, variant));
        }
        if (quantity > variant.getStock()) {
            throw new OutOfStockException(
                    "requested quantity " + quantity + " exceeds stock " + variant.getStock());
        }
        CartItem fresh = CartItem.builder().cart(cart).variant(variant).quantity(quantity).build();
        try {
            CartItemResponse response = mapper.toCartItemResponse(cartItemRepository.save(fresh));
            log.info("Added variant {} (qty {}) to cart {}", variant.getVariantId(), quantity, cart.getCartId());
            return response;
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent insert for cart {} variant {}, merging as bump",
                    cart.getCartId(), variant.getVariantId());
            CartItem raced = cartItemRepository
                    .findByCart_CartIdAndVariant_VariantId(cart.getCartId(), variant.getVariantId())
                    .orElseThrow(() -> e);
            return mapper.toCartItemResponse(saveWithCap(raced, raced.getQuantity() + quantity, variant));
        }
    }

    public CartItemResponse setQuantity(String customerId, String cartItemId, CartItemUpdateRequest req) {
        if (req == null || req.quantity() == null || req.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be between 1 and stock");
        }
        CartItem item = requireOwned(customerId, cartItemId);
        Variant variant = item.getVariant();
        if (req.quantity() > variant.getStock()) {
            throw new OutOfStockException(
                    "requested quantity " + req.quantity() + " exceeds stock " + variant.getStock());
        }
        item.setQuantity(req.quantity());
        CartItemResponse response = mapper.toCartItemResponse(cartItemRepository.save(item));
        log.info("Set cart item {} quantity to {}", cartItemId, req.quantity());
        return response;
    }

    public void removeItem(String customerId, String cartItemId) {
        CartItem item = requireOwned(customerId, cartItemId);
        cartItemRepository.delete(item);
        log.info("Removed cart item {} from customer {}", cartItemId, customerId);
    }

    public void clear(String customerId) {
        Cart cart = getOrCreate(customerId);
        cartItemRepository.deleteByCart_CartId(cart.getCartId());
        log.info("Cleared cart {} for customer {}", cart.getCartId(), customerId);
    }

    private Cart getOrCreate(String customerId) {
        Customer customer = customerRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("customer not found: " + customerId));
        return cartRepository.findByCustomer_CustomerId(customerId).orElseGet(() -> {
            Cart cart = cartRepository.save(Cart.builder().customer(customer).build());
            log.info("Created cart {} for customer {}", cart.getCartId(), customerId);
            return cart;
        });
    }

    private Variant requireSellable(String variantId) {
        Variant variant = variantRepository.findWithProductByVariantId(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("variant not found: " + variantId));
        if (Boolean.TRUE.equals(variant.getDeleted())
                || variant.getProduct() == null
                || variant.getProduct().getStatus() != ProductStatus.ACTIVE) {
            throw new ResourceNotFoundException("variant not found: " + variantId);
        }
        return variant;
    }

    private CartItem requireOwned(String customerId, String cartItemId) {
        Cart cart = getOrCreate(customerId);
        CartItem item = cartItemRepository.findByCartItemId(cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("cart item not found: " + cartItemId));
        if (item.getCart() == null || !cart.getCartId().equals(item.getCart().getCartId())) {
            throw new ResourceNotFoundException("cart item not found: " + cartItemId);
        }
        return item;
    }

    private CartItem saveWithCap(CartItem item, int nextQuantity, Variant variant) {
        if (nextQuantity > variant.getStock()) {
            throw new OutOfStockException(
                    "requested quantity " + nextQuantity + " exceeds stock " + variant.getStock());
        }
        item.setQuantity(nextQuantity);
        return cartItemRepository.save(item);
    }
}
