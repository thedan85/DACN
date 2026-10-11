package com.project.dacn.modules.cart.repository;

import com.project.dacn.modules.cart.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByCartId(String cartId);

    Optional<Cart> findByCustomer_CustomerId(String customerId);
}
