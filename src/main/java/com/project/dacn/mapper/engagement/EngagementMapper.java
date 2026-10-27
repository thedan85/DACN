package com.project.dacn.mapper.engagement;

import com.project.dacn.dto.request.engagement.NotificationCreateRequest;
import com.project.dacn.dto.request.engagement.NotificationUpdateRequest;
import com.project.dacn.dto.request.engagement.ReviewCreateRequest;
import com.project.dacn.dto.request.engagement.ReviewUpdateRequest;
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
    Notification toNotification(NotificationCreateRequest request);

    @Mapping(source = "user.userId", target = "userId")
    NotificationResponse toNotificationResponse(Notification notification);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "notificationId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "message", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateNotification(NotificationUpdateRequest request, @MappingTarget Notification notification);
}
