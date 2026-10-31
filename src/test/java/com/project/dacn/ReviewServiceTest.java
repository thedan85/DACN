package com.project.dacn;

import com.project.dacn.common.constant.OrderStatus;
import com.project.dacn.common.constant.ProductStatus;
import com.project.dacn.common.exception.DuplicateResourceException;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.request.engagement.ReviewCreateRequest;
import com.project.dacn.dto.request.engagement.ReviewUpdateRequest;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.entity.catalog.Product;
import com.project.dacn.entity.catalog.Variant;
import com.project.dacn.entity.engagement.Review;
import com.project.dacn.entity.identity.Customer;
import com.project.dacn.entity.order.Order;
import com.project.dacn.entity.order.OrderItem;
import com.project.dacn.mapper.engagement.EngagementMapper;
import com.project.dacn.repository.catalog.VariantRepository;
import com.project.dacn.repository.engagement.ReviewRepository;
import com.project.dacn.repository.order.OrderItemRepository;
import com.project.dacn.repository.order.OrderRepository;
import com.project.dacn.service.engagement.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    OrderRepository orderRepository;

    @Mock
    OrderItemRepository orderItemRepository;

    @Mock
    ReviewRepository reviewRepository;

    @Mock
    VariantRepository variantRepository;

    ReviewService service;

    @BeforeEach
    void setUp() {
        service = new ReviewService(orderRepository, orderItemRepository, reviewRepository,
                variantRepository, Mappers.getMapper(EngagementMapper.class));
    }

    @Test
    void createsVerifiedReviewForPurchasedVariant() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        when(variantRepository.findByVariantId("VAR_1"))
                .thenReturn(Optional.of(f.item.getVariant()));
        when(orderItemRepository
                .findFirstByVariant_VariantIdAndOrder_Customer_IdAndOrder_StatusOrderByCreatedAtDesc(
                        "VAR_1", 42L, OrderStatus.COMPLETED))
                .thenReturn(Optional.of(f.item));
        when(reviewRepository.findByCustomer_IdAndVariant_VariantId(42L, "VAR_1"))
                .thenReturn(Optional.empty());
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review saved = invocation.getArgument(0);
            saved.setReviewId("REV_1");
            return saved;
        });

        ReviewResponse response = service.create(42L, "VAR_1",
                new ReviewCreateRequest(5, "great"));

        assertThat(response.reviewId()).isEqualTo("REV_1");
        assertThat(response.variantId()).isEqualTo("VAR_1");
        assertThat(response.verifiedPurchase()).isTrue();
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderItem()).isSameAs(f.item);
        assertThat(captor.getValue().getCustomer()).isSameAs(f.order.getCustomer());
    }

    @Test
    void rejectsReviewWithoutCompletedPurchase() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        when(variantRepository.findByVariantId("VAR_1"))
                .thenReturn(Optional.of(f.item.getVariant()));
        when(orderItemRepository
                .findFirstByVariant_VariantIdAndOrder_Customer_IdAndOrder_StatusOrderByCreatedAtDesc(
                        "VAR_1", 42L, OrderStatus.COMPLETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(42L, "VAR_1",
                new ReviewCreateRequest(5, "great")))
                .isInstanceOf(AccessDeniedException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateVerifiedReviewForSameVariant() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        when(variantRepository.findByVariantId("VAR_1"))
                .thenReturn(Optional.of(f.item.getVariant()));
        when(orderItemRepository
                .findFirstByVariant_VariantIdAndOrder_Customer_IdAndOrder_StatusOrderByCreatedAtDesc(
                        "VAR_1", 42L, OrderStatus.COMPLETED))
                .thenReturn(Optional.of(f.item));
        when(reviewRepository.findByCustomer_IdAndVariant_VariantId(42L, "VAR_1"))
                .thenReturn(Optional.of(review(f, 4, "existing")));

        assertThatThrownBy(() -> service.create(42L, "VAR_1",
                new ReviewCreateRequest(5, "great")))
                .isInstanceOf(DuplicateResourceException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    void claimsUnverifiedSeedReviewAfterPurchase() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        Review seeded = Review.builder()
                .reviewId("REV_SEED")
                .variant(f.item.getVariant())
                .customer(f.order.getCustomer())
                .rating(3)
                .comment("seed")
                .build();
        when(variantRepository.findByVariantId("VAR_1"))
                .thenReturn(Optional.of(f.item.getVariant()));
        when(orderItemRepository
                .findFirstByVariant_VariantIdAndOrder_Customer_IdAndOrder_StatusOrderByCreatedAtDesc(
                        "VAR_1", 42L, OrderStatus.COMPLETED))
                .thenReturn(Optional.of(f.item));
        when(reviewRepository.findByCustomer_IdAndVariant_VariantId(42L, "VAR_1"))
                .thenReturn(Optional.of(seeded));
        when(reviewRepository.save(seeded)).thenReturn(seeded);

        ReviewResponse response = service.create(42L, "VAR_1",
                new ReviewCreateRequest(5, "great"));

        assertThat(response.reviewId()).isEqualTo("REV_SEED");
        assertThat(response.verifiedPurchase()).isTrue();
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("great");
        assertThat(seeded.getOrderItem()).isSameAs(f.item);
    }

    @Test
    void rejectsUnknownVariant() {
        when(variantRepository.findByVariantId("VAR_MISSING"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(42L, "VAR_MISSING",
                new ReviewCreateRequest(5, "great")))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(orderItemRepository, reviewRepository);
    }

    @Test
    void updatesOwnReview() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        Review review = review(f, 3, "old");
        when(reviewRepository.findByReviewIdAndCustomer_Id("REV_1", 42L))
                .thenReturn(Optional.of(review));

        ReviewResponse response = service.update(42L, "REV_1",
                new ReviewUpdateRequest(4, "updated"));

        assertThat(response.rating()).isEqualTo(4);
        assertThat(response.comment()).isEqualTo("updated");
        assertThat(review.getRating()).isEqualTo(4);
        assertThat(review.getComment()).isEqualTo("updated");
    }

    @Test
    void hidesReviewsOwnedByAnotherCustomerOnUpdate() {
        when(reviewRepository.findByReviewIdAndCustomer_Id("REV_1", 42L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(42L, "REV_1",
                new ReviewUpdateRequest(4, "updated")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletesOwnReview() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        Review review = review(f, 5, "great");
        when(reviewRepository.findByReviewIdAndCustomer_Id("REV_1", 42L))
                .thenReturn(Optional.of(review));

        service.delete(42L, "REV_1");

        verify(reviewRepository).delete(review);
    }

    @Test
    void hidesReviewsOwnedByAnotherCustomerOnDelete() {
        when(reviewRepository.findByReviewIdAndCustomer_Id("REV_1", 42L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(42L, "REV_1"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(reviewRepository, never()).delete(any());
    }

    @Test
    void listsReviewsForOwnedOrder() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        when(orderRepository.existsByOrderIdAndCustomer_Id("ORD_1", 42L))
                .thenReturn(true);
        when(reviewRepository.findByOrderItem_Order_OrderIdOrderByCreatedAtDesc(
                eq("ORD_1"), any(Pageable.class)))
                .thenReturn(pageOf(f));

        PageResponse<ReviewResponse> response =
                service.listForOrder(42L, "ORD_1", PageRequest.of(0, 10));

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).reviewId()).isEqualTo("REV_1");
        assertThat(response.getContent().get(0).customerId()).isNull();
        assertThat(response.getTotalElements()).isEqualTo(1);
    }

    @Test
    void hidesReviewsForForeignOrder() {
        when(orderRepository.existsByOrderIdAndCustomer_Id("ORD_1", 42L))
                .thenReturn(false);

        assertThatThrownBy(() -> service.listForOrder(42L, "ORD_1", PageRequest.of(0, 10)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(reviewRepository);
    }

    @Test
    void staffCanListAnyOrderAndSeesCustomerId() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        when(orderRepository.findByOrderId("ORD_1")).thenReturn(Optional.of(f.order));
        when(reviewRepository.findByOrderItem_Order_OrderIdOrderByCreatedAtDesc(
                eq("ORD_1"), any(Pageable.class)))
                .thenReturn(pageOf(f));

        PageResponse<ReviewResponse> response =
                service.listForOrderAsStaff("ORD_1", PageRequest.of(0, 10));

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).customerId()).isEqualTo("CUS_1");
    }

    @Test
    void listsPublicVariantReviewsWithoutCustomerId() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        when(variantRepository.findWithProductByVariantId("VAR_1"))
                .thenReturn(Optional.of(f.item.getVariant()));
        when(reviewRepository.findByVariant_VariantIdOrderByCreatedAtDesc(
                eq("VAR_1"), any(Pageable.class)))
                .thenReturn(pageOf(f));

        PageResponse<ReviewResponse> response =
                service.listForVariant("VAR_1", false, PageRequest.of(0, 10));

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).customerId()).isNull();
    }

    @Test
    void hidesReviewsForInactiveVariantFromPublic() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        f.item.getVariant().getProduct().setStatus(ProductStatus.INACTIVE);
        when(variantRepository.findWithProductByVariantId("VAR_1"))
                .thenReturn(Optional.of(f.item.getVariant()));

        assertThatThrownBy(() -> service.listForVariant("VAR_1", false, PageRequest.of(0, 10)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(reviewRepository);
    }

    @Test
    void staffCanListInactiveVariantReviews() {
        Fixture f = fixture(OrderStatus.COMPLETED);
        f.item.getVariant().getProduct().setStatus(ProductStatus.INACTIVE);
        when(variantRepository.findWithProductByVariantId("VAR_1"))
                .thenReturn(Optional.of(f.item.getVariant()));
        when(reviewRepository.findByVariant_VariantIdOrderByCreatedAtDesc(
                eq("VAR_1"), any(Pageable.class)))
                .thenReturn(pageOf(f));

        PageResponse<ReviewResponse> response =
                service.listForVariant("VAR_1", true, PageRequest.of(0, 10));

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).customerId()).isEqualTo("CUS_1");
    }

    private Page<Review> pageOf(Fixture f) {
        return new PageImpl<>(List.of(review(f, 5, "great")),
                PageRequest.of(0, 10), 1);
    }

    private Review review(Fixture f, int rating, String comment) {
        return Review.builder()
                .reviewId("REV_1")
                .variant(f.item.getVariant())
                .customer(f.order.getCustomer())
                .orderItem(f.item)
                .rating(rating)
                .comment(comment)
                .build();
    }

    private Fixture fixture(OrderStatus status) {
        Customer customer = Customer.builder()
                .customerId("CUS_1").name("An").phone("090").build();
        Product product = Product.builder()
                .productId("PRD_1").slug("p").status(ProductStatus.ACTIVE)
                .name("P").description("d").build();
        Variant variant = Variant.builder()
                .product(product).variantId("VAR_1").sku("SKU-1")
                .price(BigDecimal.TEN).stock(5).build();
        Order order = Order.builder()
                .orderId("ORD_1").customer(customer).status(status)
                .total(BigDecimal.TEN).address("A").recipientName("An").phone("090")
                .build();
        OrderItem item = OrderItem.builder()
                .order(order).variant(variant).quantity(1).unitPrice(BigDecimal.TEN)
                .build();
        return new Fixture(order, item);
    }

    private record Fixture(Order order, OrderItem item) {
    }
}
