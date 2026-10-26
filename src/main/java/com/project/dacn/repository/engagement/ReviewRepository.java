package com.project.dacn.repository.engagement;

import com.project.dacn.entity.engagement.Review;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByReviewId(String reviewId);

    List<Review> findByVariant_VariantId(String variantId);

    @EntityGraph(attributePaths = {"customer"})
    List<Review> findByVariant_Product_ProductIdOrderByCreatedAtDesc(String productId);
}
