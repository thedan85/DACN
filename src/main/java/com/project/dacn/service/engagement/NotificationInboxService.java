package com.project.dacn.service.engagement;

import com.project.dacn.common.constant.NotificationStatus;
import com.project.dacn.common.response.PageResponse;
import com.project.dacn.dto.response.engagement.NotificationInboxResponse;
import com.project.dacn.dto.response.engagement.NotificationResponse;
import com.project.dacn.dto.response.engagement.NotificationUnreadCountResponse;
import com.project.dacn.repository.engagement.NotificationInboxQueries;
import com.project.dacn.repository.engagement.NotificationInboxQueries.Counts;
import com.project.dacn.repository.engagement.NotificationInboxQueries.Projection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Bounded, repeatable-read inbox snapshot service for the authenticated owner. */
@Service
public class NotificationInboxService {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final NotificationInboxQueries queries;
    private final TransactionTemplate tx;

    public NotificationInboxService(NotificationInboxQueries queries,
                                    PlatformTransactionManager transactionManager) {
        this.queries = queries;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.tx.setReadOnly(true);
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
}
