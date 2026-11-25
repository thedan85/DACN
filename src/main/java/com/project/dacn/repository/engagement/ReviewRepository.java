package com.project.dacn.repository.engagement;

import com.project.dacn.entity.engagement.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @EntityGraph(attributePaths = {"customer", "variant", "variant.product"})
    @Query("""
            select r from Review r
            where (:rating is null or r.rating = :rating)
              and (:keyword is null
                   or lower(r.comment) like lower(concat('%', :keyword, '%'))
                   or lower(r.customer.name) like lower(concat('%', :keyword, '%'))
                   or lower(r.variant.product.name) like lower(concat('%', :keyword, '%')))
            """)
    Page<Review> findForAdmin(@Param("keyword") String keyword,
            @Param("rating") Integer rating,
            Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Review r where r.reviewId = :reviewId")
    int deleteByReviewId(@Param("reviewId") String reviewId);
}
