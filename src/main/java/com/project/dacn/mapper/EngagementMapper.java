package com.project.dacn.mapper;

import com.project.dacn.dto.request.NotificationCreateRequest;
import com.project.dacn.dto.request.NotificationUpdateRequest;
import com.project.dacn.dto.request.ReviewCreateRequest;
import com.project.dacn.dto.request.ReviewUpdateRequest;
import com.project.dacn.dto.response.NotificationResponse;
import com.project.dacn.dto.response.ReviewResponse;
import com.project.dacn.entity.Notification;
import com.project.dacn.entity.Review;
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
    @Mapping(source = "customer.customerId", target = "customerId")
    @Mapping(source = "orderItem.orderItemId", target = "orderItemId")
    ReviewResponse toReviewResponse(Review review);

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
