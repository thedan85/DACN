package com.project.dacn.modules.engagement.repository;

import com.project.dacn.modules.engagement.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByReviewId(String reviewId);

    List<Review> findByVariant_VariantId(String variantId);
}
