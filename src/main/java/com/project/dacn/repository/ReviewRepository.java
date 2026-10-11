package com.project.dacn.repository;

import com.project.dacn.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByReviewId(String reviewId);

    List<Review> findByVariant_VariantId(String variantId);
}
