package com.project.dacn.repository.cart;

import com.project.dacn.entity.cart.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    Optional<CartItem> findByCartItemId(String cartItemId);

    List<CartItem> findByCart_CartId(String cartId);
}
