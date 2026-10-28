package com.project.dacn.repository.cart;

import com.project.dacn.entity.cart.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByCartId(String cartId);

    Optional<Cart> findByCustomer_Id(Long customerId);

    /**
     * Serializes checkouts for one customer: the second request waits for the
     * first to commit, then sees an empty cart.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.customer.id = :customerId")
    Optional<Cart> findByCustomer_IdForUpdate(@Param("customerId") Long customerId);
}
