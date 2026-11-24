package com.project.dacn.repository.engagement;

import com.project.dacn.entity.engagement.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByNotificationId(String notificationId);

    List<Notification> findByUser_UserId(String userId);

    /**
     * Native owned-row lookup. The entity's global {@code is_deleted = false}
     * restriction is intentionally bypassed here so a repeat deletion can find
     * an already-deleted owned row. Callers must still apply visibility and
     * eligibility policy and must never expose a deleted row to a foreign caller.
     */
    @Query(value = """
            SELECT * FROM notifications
             WHERE notification_id = :notificationId
               AND user_id = :userId
            """, nativeQuery = true)
    Optional<Notification> findOwnedIncludingDeleted(
            @Param("notificationId") String notificationId,
            @Param("userId") long userId);
}
