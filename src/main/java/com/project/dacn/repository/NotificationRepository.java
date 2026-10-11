package com.project.dacn.repository;

import com.project.dacn.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByNotificationId(String notificationId);

    List<Notification> findByUser_UserId(String userId);
}
