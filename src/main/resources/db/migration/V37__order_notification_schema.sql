-- =========================================================
-- V37: Order-notification schema
--
-- Inbox revisions, event-backed notification columns, the SMTP
-- delivery ledger, and the in-app consumer receipt. Dedup evidence
-- is independent of notifications and the event outbox.
-- =========================================================

alter table users add column if not exists inbox_revision bigint not null default 0;

alter table notifications
    add column source_event_id uuid null,
    add column order_id bigint null,
    add column event_type varchar(40) null,
    add column order_sequence bigint null,
    add column occurred_at timestamptz null,
    add column recipient_scope varchar(20) null,
    add constraint fk_notifications_order
        foreign key (order_id) references orders(id) on delete restrict;

-- Only legacy creation time is known; the other source fields stay absent.
update notifications set occurred_at = created_at where occurred_at is null;

alter table notifications
    add constraint chk_notifications_order_sequence
        check (order_sequence is null or order_sequence > 0),
    add constraint chk_notifications_recipient_scope
        check (recipient_scope is null or recipient_scope in ('CUSTOMER', 'COMPANY')),
    add constraint chk_notifications_event_backed_all_or_none check (
        (
            source_event_id is null
            and order_id is null
            and event_type is null
            and order_sequence is null
            and recipient_scope is null
        )
        or
        (
            source_event_id is not null
            and order_id is not null
            and event_type is not null
            and order_sequence is not null
            and occurred_at is not null
            and recipient_scope is not null
        )
    ),
    add constraint uq_notifications_event_user unique (source_event_id, user_id);

create index idx_notifications_inbox_visible
    on notifications (user_id, occurred_at desc, order_sequence desc nulls last, id desc)
    where is_deleted = false;

create index idx_notifications_unread
    on notifications (user_id)
    where is_deleted = false and notification_status = 'UNREAD';

create table email_deliveries (
    id bigint generated always as identity primary key,
    event_id uuid not null,
    recipient_user_id bigint not null references users(id) on delete restrict,
    state varchar(20) not null,
    attempt_count integer not null default 0 check (attempt_count >= 0),
    claim_token uuid,
    claim_expires_at timestamptz,
    next_eligible_at timestamptz,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    completed_at timestamptz,
    failure_code varchar(64),
    constraint uq_email_deliveries_event_recipient unique (event_id, recipient_user_id),
    constraint chk_email_deliveries_state check (
        state in ('PENDING', 'IN_FLIGHT', 'SENT', 'SKIPPED', 'UNKNOWN', 'FAILED')
    )
);
create index idx_email_deliveries_due
    on email_deliveries (next_eligible_at, id)
    where state in ('PENDING', 'IN_FLIGHT') and completed_at is null;
create index idx_email_deliveries_claim_expiry
    on email_deliveries (claim_expires_at)
    where state = 'IN_FLIGHT' and completed_at is null;

create table notification_event_receipts (
    id bigint generated always as identity primary key,
    event_id uuid not null,
    recipient_user_ids jsonb not null default '[]'::jsonb
        check (jsonb_typeof(recipient_user_ids) = 'array'),
    claimed_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    failure_code varchar(64),
    constraint uq_notification_event_receipts_event unique (event_id)
);
create index idx_notification_event_receipts_pending
    on notification_event_receipts (id)
    where completed_at is null;

-- Supabase grants anon/authenticated on new public objects by default; the
-- notification tables must never be browser-writable.
do $$
declare
    v_role text;
begin
    for v_role in
        select rolname from pg_roles where rolname in ('anon', 'authenticated')
    loop
        execute format('revoke all on table public.email_deliveries,
                             public.notification_event_receipts
            from %I', v_role);
    end loop;
end;
$$;
