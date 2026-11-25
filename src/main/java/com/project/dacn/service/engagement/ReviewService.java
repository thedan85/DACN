package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.DuplicateResourceException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.engagement.ReviewCreateRequest;
import com.project.dacn.dto.request.engagement.ReviewUpdateRequest;
import com.project.dacn.dto.response.engagement.AdminReviewResponse;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.engagement.Review;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.mapper.engagement.EngagementMapper;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.engagement.ReviewRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.service.catalog.CatalogCache;
import com.project.dacn.service.catalog.CatalogCacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.function.Function;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReviewService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ReviewRepository reviewRepository;
    private final VariantRepository variantRepository;
    private final EngagementMapper mapper;
    private final CatalogCache cache;

    private static final Set<String> ADMIN_SORT_FIELDS = Set.of("createdAt");

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
            bumpProductCache();
            return mapper.toReviewResponse(reviewRepository.save(review));
        }
        Review review = mapper.toReview(request);
        review.setVariant(variant);
        review.setCustomer(item.getOrder().getCustomer());
        review.setOrderItem(item);
        bumpProductCache();
        return mapper.toReviewResponse(reviewRepository.save(review));
    }

    @Transactional
    public ReviewResponse update(Long customerId, String reviewId, ReviewUpdateRequest request) {
        Review review = ownedReview(customerId, reviewId);
        mapper.updateReview(request, review);
        bumpProductCache();
        return mapper.toReviewResponse(review);
    }

    @Transactional
    public void delete(Long customerId, String reviewId) {
        reviewRepository.delete(ownedReview(customerId, reviewId));
        bumpProductCache();
    }

    public PageResponse<AdminReviewResponse> listForAdmin(
            String keyword, Integer rating, Pageable pageable) {
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        validateAdminSort(pageable);
        Pageable effective = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "createdAt"))
                : pageable;
        Page<Review> reviews = reviewRepository.findForAdmin(
                trimToNull(keyword), rating, effective);
        return PageResponse.from(reviews,
                reviews.getContent().stream().map(mapper::toAdminReviewResponse).toList());
    }

    @Transactional
    public void deleteAsAdmin(String reviewId, String actorId) {
        if (reviewRepository.deleteByReviewId(reviewId) == 0) {
            throw new ResourceNotFoundException("review not found: " + reviewId);
        }
        bumpProductCache();
        log.info("admin review action: actor={}, target={}, action=DELETE", actorId, reviewId);
    }

    private static void validateAdminSort(Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!ADMIN_SORT_FIELDS.contains(order.getProperty())) {
                throw new IllegalArgumentException(
                        "unsupported sort field: " + order.getProperty());
            }
        });
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void bumpProductCache() {
        cache.bumpAfterCommit(CatalogCacheKeys.PRODUCT_SCOPE);
    }

    public PageResponse<ReviewResponse> listForOrder(Long customerId, String orderId, Pageable pageable) {
        if (!orderRepository.existsByOrderIdAndCustomer_Id(orderId, customerId)) {
            throw new ResourceNotFoundException("order not found: " + orderId);
        }
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
