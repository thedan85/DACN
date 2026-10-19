package com.project.dacn.repository.cart;

import com.project.dacn.entity.cart.CartItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByCart_Id(Long cartId);

    Optional<CartItem> findByCart_IdAndVariant_Id(Long cartId, Long variantId);

    void deleteByCart_Id(Long cartId);

    @EntityGraph(attributePaths = {"variant", "variant.product"})
    List<CartItem> findWithDetailsByCart_Id(Long cartId);
}
