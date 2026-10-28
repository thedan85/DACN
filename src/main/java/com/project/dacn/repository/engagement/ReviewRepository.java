package com.project.dacn.repository.engagement;

import com.project.dacn.entity.engagement.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByReviewId(String reviewId);

    Optional<Review> findByReviewIdAndCustomer_Id(String reviewId, Long customerId);

    Optional<Review> findByCustomer_IdAndVariant_VariantId(Long customerId, String variantId);

    List<Review> findByVariant_VariantId(String variantId);

    @EntityGraph(attributePaths = {"customer", "variant"})
    Page<Review> findByVariant_VariantIdOrderByCreatedAtDesc(String variantId, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "variant"})
    Page<Review> findByVariant_Product_ProductIdOrderByCreatedAtDesc(String productId, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "variant"})
    Page<Review> findByOrderItem_Order_OrderIdOrderByCreatedAtDesc(String orderId, Pageable pageable);
}
