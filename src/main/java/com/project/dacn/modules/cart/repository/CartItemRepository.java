package com.project.dacn.modules.cart.repository;

import com.project.dacn.modules.cart.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    Optional<CartItem> findByCartItemId(String cartItemId);

    List<CartItem> findByCart_CartId(String cartId);
}
