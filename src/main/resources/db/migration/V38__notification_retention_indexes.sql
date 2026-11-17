-- =========================================================
-- V38: Indexes supporting bounded notification retention.
--
-- Narrow partial indexes only; no schema or data change and no
-- cascade. Each index backs one bounded prune path in
-- NotificationRetentionPruner.
-- =========================================================

-- The prune predicate uses the same coalesce expression, so index that expression.
create index if not exists idx_notifications_occurred_retention
    on notifications (coalesce(occurred_at, created_at), id);

create index if not exists idx_event_outbox_published_retention
    on event_outbox (published_at)
    where published_at is not null and quarantined_at is null;

create index if not exists idx_email_deliveries_completed_retention
    on email_deliveries (completed_at)
    where state in ('SENT', 'SKIPPED') and completed_at is not null;

create index if not exists idx_notification_event_receipts_completed_retention
    on notification_event_receipts (completed_at)
    where completed_at is not null;
