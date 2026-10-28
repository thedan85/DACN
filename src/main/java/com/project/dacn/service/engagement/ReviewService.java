package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.DuplicateResourceException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.engagement.ReviewCreateRequest;
import com.project.dacn.dto.request.engagement.ReviewUpdateRequest;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.engagement.Review;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.mapper.engagement.EngagementMapper;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.engagement.ReviewRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Function;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReviewService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ReviewRepository reviewRepository;
    private final VariantRepository variantRepository;
    private final EngagementMapper mapper;

    /**
     * Customers may review a variant only after buying it in a completed order
     * (successful shipment). The matching order line is recorded so the review
     * is marked as a verified purchase.
     */
    @Transactional
    public ReviewResponse create(Long customerId, String variantId, ReviewCreateRequest request) {
        Variant variant = variantRepository.findByVariantId(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("variant not found: " + variantId));
        OrderItem item = orderItemRepository
                .findFirstByVariant_VariantIdAndOrder_Customer_IdAndOrder_StatusOrderByCreatedAtDesc(
                        variantId, customerId, OrderStatus.COMPLETED)
                .orElseThrow(() -> new AccessDeniedException(
                        "variant not purchased or order not completed: " + variantId));
        var existing = reviewRepository.findByCustomer_IdAndVariant_VariantId(
                customerId, variantId);
        if (existing.isPresent()) {
            Review review = existing.get();
            if (review.getOrderItem() != null) {
                throw new DuplicateResourceException(
                        "review already exists for variant: " + variantId);
            }
            // Legacy/demo review created before verified purchases existed:
            // claim it with this completed order instead of returning 409.
            review.setVariant(variant);
            review.setCustomer(item.getOrder().getCustomer());
            review.setOrderItem(item);
            review.setRating(request.rating());
            review.setComment(request.comment());
            return mapper.toReviewResponse(reviewRepository.save(review));
        }
        Review review = mapper.toReview(request);
        review.setVariant(variant);
        review.setCustomer(item.getOrder().getCustomer());
        review.setOrderItem(item);
        return mapper.toReviewResponse(reviewRepository.save(review));
    }

    @Transactional
    public ReviewResponse update(Long customerId, String reviewId, ReviewUpdateRequest request) {
        Review review = ownedReview(customerId, reviewId);
        mapper.updateReview(request, review);
        return mapper.toReviewResponse(review);
    }

    @Transactional
    public void delete(Long customerId, String reviewId) {
        reviewRepository.delete(ownedReview(customerId, reviewId));
    }

    public PageResponse<ReviewResponse> listForOrder(Long customerId, String orderId, Pageable pageable) {
        orderRepository.findByOrderIdAndCustomer_Id(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("order not found: " + orderId));
        return toPage(reviewRepository.findByOrderItem_Order_OrderIdOrderByCreatedAtDesc(
                orderId, pageable), false);
    }

    public PageResponse<ReviewResponse> listForOrderAsStaff(String orderId, Pageable pageable) {
        orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("order not found: " + orderId));
        return toPage(reviewRepository.findByOrderItem_Order_OrderIdOrderByCreatedAtDesc(
                orderId, pageable), true);
    }

    /**
     * Public callers only see ACTIVE catalog rows and a reviewer name. Staff with
     * product visibility rights also see the customer id.
     */
    public PageResponse<ReviewResponse> listForVariant(
            String variantId, boolean staffView, Pageable pageable) {
        Variant variant = variantRepository.findWithProductByVariantId(variantId)
                .filter(v -> staffView || v.getProduct().getStatus() == ProductStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("variant not found: " + variantId));
        return toPage(reviewRepository.findByVariant_VariantIdOrderByCreatedAtDesc(
                variant.getVariantId(), pageable), staffView);
    }

    /**
     * Public reviews for all variants of a product, used by product detail.
     * customerId is omitted for non-staff callers.
     */
    public PageResponse<ReviewResponse> listForProduct(String productId, boolean staffView,
            Pageable pageable) {
        return toPage(reviewRepository.findByVariant_Product_ProductIdOrderByCreatedAtDesc(
                productId, pageable), staffView);
    }

    private Review ownedReview(Long customerId, String reviewId) {
        return reviewRepository.findByReviewIdAndCustomer_Id(reviewId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("review not found: " + reviewId));
    }

    private PageResponse<ReviewResponse> toPage(Page<Review> reviews, boolean staffView) {
        Function<Review, ReviewResponse> toResponse =
                staffView ? mapper::toStaffReviewResponse : mapper::toReviewResponse;
        return PageResponse.from(reviews,
                reviews.getContent().stream().map(toResponse).toList());
    }
}
