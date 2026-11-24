package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.common.exception.ResourceNotFoundException;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.response.engagement.NotificationCountResponse;
import com.project.dacn.dto.response.engagement.NotificationInboxResponse;
import com.project.dacn.dto.response.engagement.NotificationReadResponse;
import com.project.dacn.dto.response.engagement.NotificationResponse;
import com.project.dacn.dto.response.engagement.NotificationUnreadCountResponse;
import com.project.dacn.entity.engagement.Notification;
import com.project.dacn.entity.engagement.NotificationRecipientScope;
import com.project.dacn.repository.engagement.NotificationInboxQueries;
import com.project.dacn.repository.engagement.NotificationInboxQueries.Counts;
import com.project.dacn.repository.engagement.NotificationInboxQueries.MutationState;
import com.project.dacn.repository.engagement.NotificationInboxQueries.Projection;
import com.project.dacn.repository.engagement.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Bounded inbox snapshots and owned read/delete mutations for the authenticated owner. */
@Service
public class NotificationInboxService {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final NotificationInboxQueries queries;
    private final NotificationRepository repository;
    private final TransactionTemplate tx;
    private final TransactionTemplate writeTx;

    /** Read-only construction used by snapshot-only callers and tests. */
    public NotificationInboxService(NotificationInboxQueries queries,
                                    PlatformTransactionManager transactionManager) {
        this(queries, null, transactionManager);
    }

    @Autowired
    public NotificationInboxService(NotificationInboxQueries queries,
                                    NotificationRepository repository,
                                    PlatformTransactionManager transactionManager) {
        this.queries = queries;
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.tx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * One read-only repeatable-read transaction: owner/revision/eligibility
     * projection, one content query, and one combined aggregate. A concurrent
     * commit between reads is not visible inside this snapshot.
     */
    public NotificationInboxResponse getInbox(String principalBusinessId, int page, int size,
                                              NotificationStatus filter) {
        int safePage = Math.max(DEFAULT_PAGE, page);
        int safeSize = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return tx.execute(status -> {
            Projection projection = queries.projection(principalBusinessId);
            if (projection.userId() == null) {
                return new NotificationInboxResponse(
                        new PageResponse<>(List.of(), safePage, safeSize, 0L, 0), 0L, 0L);
            }
            List<NotificationResponse> rows =
                    queries.content(projection.userId(), projection.companyEligible(),
                            filter, safePage, safeSize);
            Counts counts = queries.counts(projection.userId(), projection.companyEligible(), filter);
            int totalPages = (int) Math.ceil((double) counts.filteredTotal() / safeSize);
            PageResponse<NotificationResponse> content =
                    new PageResponse<>(rows, safePage, safeSize, counts.filteredTotal(), totalPages);
            return new NotificationInboxResponse(content, counts.globalUnread(), projection.revision());
        });
    }

    /** Bell-only snapshot; same snapshot rules, no message content. */
    public NotificationUnreadCountResponse unreadCount(String principalBusinessId) {
        return tx.execute(status -> {
            Projection projection = queries.projection(principalBusinessId);
            if (projection.userId() == null) {
                return new NotificationUnreadCountResponse(0L, 0L);
            }
            Counts counts = queries.counts(projection.userId(), projection.companyEligible(), null);
            return new NotificationUnreadCountResponse(counts.globalUnread(), projection.revision());
        });
    }

    /**
     * Owned READ mutation. Locks the recipient user before the notification row,
     * advances the database-owned revision only on an actual change, and returns
     * counts/revision read from SQL inside the same transaction.
     */
    public NotificationReadResponse markRead(String principalBusinessId, String notificationId) {
        return writeTx.execute(status -> {
            Projection projection = queries.lockProjection(principalBusinessId);
            if (projection.userId() == null) {
                throw new ResourceNotFoundException("notification not found");
            }
            Notification row = requireOwnedVisible(notificationId, projection);
            if (Boolean.TRUE.equals(row.getDeleted())) {
                throw new ResourceNotFoundException("notification not found");
            }
            if (queries.markRead(projection.userId(), notificationId, projection.companyEligible()) > 0) {
                queries.incrementInboxRevision(projection.userId());
            }
            MutationState state = queries.mutationState(projection.userId(), projection.companyEligible());
            return new NotificationReadResponse(toResponse(row, NotificationStatus.READ),
                    state.unreadCount(), state.revision());
        });
    }

    /** Owned soft-delete mutation; repeating it on an owned row is a no-op. */
    public NotificationCountResponse delete(String principalBusinessId, String notificationId) {
        return writeTx.execute(status -> {
            Projection projection = queries.lockProjection(principalBusinessId);
            if (projection.userId() == null) {
                throw new ResourceNotFoundException("notification not found");
            }
            requireOwnedVisible(notificationId, projection);
            if (queries.softDelete(projection.userId(), notificationId, projection.companyEligible()) > 0) {
                queries.incrementInboxRevision(projection.userId());
            }
            MutationState state = queries.mutationState(projection.userId(), projection.companyEligible());
            return new NotificationCountResponse(state.unreadCount(), state.revision());
        });
    }

    /**
     * Resolves an owned row including an already-deleted one. A foreign caller
     * never sees the row at all; a COMPANY row whose current authority was lost
     * is not authorized. Visibility matches the snapshot service's policy.
     */
    private Notification requireOwnedVisible(String notificationId, Projection projection) {
        Notification row = repository.findOwnedIncludingDeleted(notificationId, projection.userId())
                .orElseThrow(() -> new ResourceNotFoundException("notification not found"));
        if (row.getRecipientScope() == NotificationRecipientScope.COMPANY
                && !projection.companyEligible()) {
            throw new AccessDeniedException("current company authority is required");
        }
        return row;
    }

    private static NotificationResponse toResponse(Notification row, NotificationStatus status) {
        return new NotificationResponse(
                row.getNotificationId(),
                row.getUser().getUserId(),
                row.getMessage(),
                row.getType(),
                status,
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getOrder() == null ? null : row.getOrder().getOrderId(),
                row.getEventType(),
                row.getOrderSequence(),
                row.getOccurredAt());
    }
}
