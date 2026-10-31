package com.project.dacn.service.cart;

import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.OutOfStockException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.dto.request.cart.CartCheckoutRequest;
import com.project.dacn.dto.request.cart.CartItemAddRequest;
import com.project.dacn.dto.request.cart.CartItemUpdateRequest;
import com.project.dacn.dto.request.order.OrderCreateRequest;
import com.project.dacn.dto.request.payment.PaymentCreateRequest;
import com.project.dacn.dto.response.cart.CartDetailResponse;
import com.project.dacn.dto.response.cart.CartItemResponse;
import com.project.dacn.dto.response.order.OrderItemResponse;
import com.project.dacn.dto.response.order.OrderResponse;
import com.project.dacn.entity.cart.Cart;
import com.project.dacn.entity.cart.CartItem;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.entity.payment.Payment;
import com.project.dacn.mapper.cart.CartMapper;
import com.project.dacn.mapper.order.OrderMapper;
import com.project.dacn.mapper.payment.PaymentMapper;
import com.project.dacn.repository.cart.CartItemRepository;
import com.project.dacn.repository.cart.CartRepository;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.identity.CustomerRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.repository.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;

    public CartDetailResponse getDetail(Long customerId) {
        Cart cart = getOrCreate(customerId);
        List<CartItem> items = cartItemRepository.findWithDetailsByCart_Id(cart.getId());
        List<CartItemResponse> responses = items.stream().map(mapper::toCartItemResponse).toList();
        return mapper.toDetail(cart, responses);
    }

    public CartItemResponse addItem(Long customerId, CartItemAddRequest req) {
        if (req == null || req.quantity() == null || req.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        requireCustomer(customerId);
        Variant variant = requireSellable(req.variantId());
        Cart cart = getOrCreate(customerId);
        int quantity = req.quantity();
        Optional<CartItem> existing =
                cartItemRepository.findByCart_IdAndVariant_Id(cart.getId(), variant.getId());
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
                    .findByCart_IdAndVariant_Id(cart.getId(), variant.getId())
                    .orElseThrow(() -> e);
            return mapper.toCartItemResponse(saveWithCap(raced, raced.getQuantity() + quantity, variant));
        }
    }

    public CartItemResponse setQuantity(Long customerId, String cartItemId, CartItemUpdateRequest req) {
        if (req == null || req.quantity() == null || req.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be between 1 and stock");
        }
        CartItem item = requireOwned(customerId, cartItemId);
        Variant current = item.getVariant();
        if (current == null || current.getVariantId() == null) {
            throw new ResourceNotFoundException("variant not found: null");
        }
        Variant variant = requireSellable(current.getVariantId());
        if (req.quantity() > variant.getStock()) {
            throw new OutOfStockException(
                    "requested quantity " + req.quantity() + " exceeds stock " + variant.getStock());
        }
        item.setQuantity(req.quantity());
        CartItemResponse response = mapper.toCartItemResponse(cartItemRepository.save(item));
        log.info("Set cart item {} quantity to {}", cartItemId, req.quantity());
        return response;
    }

    public void removeItem(Long customerId, String cartItemId) {
        CartItem item = requireOwned(customerId, cartItemId);
        cartItemRepository.delete(item);
        log.info("Removed cart item {} from customer {}", cartItemId, customerId);
    }

    public void clear(Long customerId) {
        Cart cart = requireCart(customerId);
        cartItemRepository.deleteByCart_Id(cart.getId());
        log.info("Cleared cart {} for customer {}", cart.getCartId(), customerId);
    }

    public OrderResponse checkout(Long customerId, CartCheckoutRequest req) {
        Customer customer = requireCustomer(customerId);
        Cart cart = cartRepository.findByCustomer_IdForUpdate(customerId).orElse(null);
        List<CartItem> items = cart == null
                ? List.of()
                : cartItemRepository.findWithDetailsByCart_Id(cart.getId());
        if (items.isEmpty()) {
            throw new IllegalArgumentException("cart is empty");
        }
        for (CartItem item : items) {
            Variant variant = item.getVariant();
            if (variant == null || Boolean.TRUE.equals(variant.getDeleted())
                    || variant.getProduct() == null
                    || variant.getProduct().getStatus() != ProductStatus.ACTIVE) {
                throw new ResourceNotFoundException("variant not found: "
                        + (variant == null ? null : variant.getVariantId()));
            }
            if (item.getQuantity() > variant.getStock()) {
                throw new OutOfStockException("requested quantity " + item.getQuantity()
                        + " exceeds stock " + variant.getStock());
            }
        }
        for (CartItem item : items) {
            int updated = variantRepository.decrementStock(
                    item.getVariant().getId(), item.getQuantity());
            if (updated == 0) {
                throw new OutOfStockException("insufficient stock for variant "
                        + item.getVariant().getId());
            }
        }
        BigDecimal total = items.stream()
                .map(item -> item.getVariant().getPrice()
                        .multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Order order = orderMapper.toOrder(new OrderCreateRequest(
                customer.getCustomerId(), total, req.address(), req.recipientName(), req.phone()));
        order.setCustomer(customer);
        Order saved = orderRepository.save(order);
        List<OrderItem> lines = new ArrayList<>(items.size());
        for (CartItem item : items) {
            lines.add(OrderItem.builder()
                    .order(saved)
                    .variant(item.getVariant())
                    .quantity(item.getQuantity())
                    .unitPrice(item.getVariant().getPrice())
                    .build());
        }
        List<OrderItem> savedLines = orderItemRepository.saveAll(lines);
        Payment payment = paymentMapper.toPayment(new PaymentCreateRequest(
                saved.getOrderId(), req.method(), total, null));
        payment.setOrder(saved);
        Payment savedPayment = paymentRepository.save(payment);
        cartItemRepository.deleteByCart_Id(cart.getId());
        log.info("Checked out cart {} to order {} (total {}) for customer {}",
                cart.getCartId(), saved.getOrderId(), total, customerId);
        List<OrderItemResponse> lineResponses = savedLines.stream()
                .map(orderMapper::toOrderItemResponse).toList();
        return orderMapper.toOrderResponse(saved, lineResponses,
                paymentMapper.toPaymentResponse(savedPayment));
    }

    private Customer requireCustomer(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("customer not found: " + customerId));
    }

    private Cart requireCart(Long customerId) {
        requireCustomer(customerId);
        return cartRepository.findByCustomer_Id(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("cart not found: " + customerId));
    }

    private Cart getOrCreate(Long customerId) {
        Customer customer = requireCustomer(customerId);
        return cartRepository.findByCustomer_Id(customerId).orElseGet(() -> {
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

    private CartItem requireOwned(Long customerId, String cartItemId) {
        Cart cart = requireCart(customerId);
        CartItem item = cartItemRepository.findByCartItemId(cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("cart item not found: " + cartItemId));
        if (item.getCart() == null || !Objects.equals(cart.getId(), item.getCart().getId())) {
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
