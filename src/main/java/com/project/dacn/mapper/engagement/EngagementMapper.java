package com.project.dacn.mapper.engagement;

import com.project.dacn.dto.request.engagement.NotificationCreateRequest;
import com.project.dacn.dto.request.engagement.NotificationUpdateRequest;
import com.project.dacn.dto.request.engagement.ReviewCreateRequest;
import com.project.dacn.dto.request.engagement.ReviewUpdateRequest;
import com.project.dacn.dto.response.engagement.AdminReviewResponse;
import com.project.dacn.dto.response.engagement.NotificationResponse;
import com.project.dacn.dto.response.engagement.ReviewResponse;
import com.project.dacn.entity.engagement.Notification;
import com.project.dacn.entity.engagement.Review;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface EngagementMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "reviewId", ignore = true)
    @Mapping(target = "variant", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "orderItem", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Review toReview(ReviewCreateRequest request);

    @Mapping(source = "variant.variantId", target = "variantId")
    @Mapping(target = "customerId", ignore = true)
    @Mapping(source = "customer.name", target = "customerName")
    @Mapping(target = "verifiedPurchase", expression = "java(review.getOrderItem() != null)")
    ReviewResponse toReviewResponse(Review review);

    @Mapping(source = "variant.variantId", target = "variantId")
    @Mapping(source = "customer.customerId", target = "customerId")
    @Mapping(source = "customer.name", target = "customerName")
    @Mapping(target = "verifiedPurchase", expression = "java(review.getOrderItem() != null)")
    ReviewResponse toStaffReviewResponse(Review review);

    @Mapping(source = "customer.customerId", target = "customerId")
    @Mapping(source = "customer.name", target = "customerName")
    @Mapping(source = "variant.variantId", target = "variantId")
    @Mapping(source = "variant.sku", target = "variantSku")
    @Mapping(source = "variant.product.productId", target = "productId")
    @Mapping(source = "variant.product.name", target = "productName")
    @Mapping(target = "verifiedPurchase", expression = "java(review.getOrderItem() != null)")
    AdminReviewResponse toAdminReviewResponse(Review review);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "reviewId", ignore = true)
    @Mapping(target = "variant", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "orderItem", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateReview(ReviewUpdateRequest request, @MappingTarget Review review);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "notificationId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "type", constant = "SYSTEM")
    @Mapping(target = "status", constant = "UNREAD")
    @Mapping(target = "deleted", constant = "false")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "sourceEventId", ignore = true)
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "eventType", ignore = true)
    @Mapping(target = "orderSequence", ignore = true)
    @Mapping(target = "occurredAt", ignore = true)
    @Mapping(target = "recipientScope", ignore = true)
    Notification toNotification(NotificationCreateRequest request);

    @Mapping(source = "user.userId", target = "userId")
    @Mapping(source = "order.orderId", target = "orderId")
    NotificationResponse toNotificationResponse(Notification notification);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "notificationId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "message", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "sourceEventId", ignore = true)
    @Mapping(target = "order", ignore = true)
    @Mapping(target = "eventType", ignore = true)
    @Mapping(target = "orderSequence", ignore = true)
    @Mapping(target = "occurredAt", ignore = true)
    @Mapping(target = "recipientScope", ignore = true)
    void updateNotification(NotificationUpdateRequest request, @MappingTarget Notification notification);
}
