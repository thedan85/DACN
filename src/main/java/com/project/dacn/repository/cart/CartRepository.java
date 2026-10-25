package com.project.dacn.repository.cart;

import com.project.dacn.entity.cart.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByCartId(String cartId);

    Optional<Cart> findByCustomer_Id(Long customerId);
}
