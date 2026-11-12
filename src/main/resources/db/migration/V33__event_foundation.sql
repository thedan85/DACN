-- =========================================================
-- V30: Event foundation
--
-- Order event sequence, transactional event outbox, realtime
-- control/budget, token leases, and the best-effort realtime
-- hint enqueue function.
-- =========================================================

alter table orders add column if not exists event_sequence bigint not null default 0;
alter table orders add constraint chk_orders_event_sequence check (event_sequence >= 0);

create table event_outbox (
    id bigint generated always as identity primary key,
    event_id uuid not null unique,
    order_id bigint not null references orders(id) on delete restrict,
    order_sequence bigint not null check (order_sequence > 0),
    event_type varchar(40) not null,
    schema_version integer not null check (schema_version > 0),
    payload jsonb not null,
    created_at timestamptz not null default clock_timestamp(),
    available_at timestamptz not null,
    lease_owner varchar(100),
    lease_token uuid,
    lease_expires_at timestamptz,
    attempt_count integer not null default 0 check (attempt_count >= 0),
    published_at timestamptz,
    quarantined_at timestamptz,
    last_error_code varchar(64),
    constraint uq_event_outbox_order_sequence unique (order_id, order_sequence),
    constraint chk_event_outbox_terminal check (
        not (published_at is not null and quarantined_at is not null)
    )
);

create index idx_event_outbox_due
    on event_outbox (available_at, id)
    where published_at is null and quarantined_at is null;

create table app_realtime_control (
    id smallint primary key check (id = 1),
    enabled boolean not null default false,
    remaining_message_budget bigint not null default 0
        check (remaining_message_budget >= 0),
    updated_at timestamptz not null default clock_timestamp()
);
insert into app_realtime_control (id, enabled, remaining_message_budget)
values (1, false, 0)
on conflict (id) do nothing;

create table realtime_token_leases (
    jti uuid primary key,
    user_id bigint references users(id) on delete cascade,
    admitted_product_ids jsonb not null default '[]'::jsonb
        check (jsonb_typeof(admitted_product_ids) = 'array'),
    inbox_allowed boolean not null default false,
    expires_at timestamptz not null,
    retire_at timestamptz not null,
    constraint chk_realtime_token_leases_retire check (retire_at >= expires_at)
);
create index idx_realtime_token_leases_retire_at
    on realtime_token_leases (retire_at);
create index idx_realtime_token_leases_products
    on realtime_token_leases using gin (admitted_product_ids jsonb_path_ops);

create table realtime_signal_outbox (
    id bigint generated always as identity primary key,
    topic varchar(300) not null,
    event_name varchar(32) not null,
    payload jsonb not null,
    created_at timestamptz not null default clock_timestamp(),
    sent_at timestamptz,
    reserved_message_cost integer check (reserved_message_cost >= 0)
);
create index idx_realtime_signal_outbox_pending
    on realtime_signal_outbox (id) where sent_at is null;
create index idx_realtime_signal_outbox_recent
    on realtime_signal_outbox (sent_at) where sent_at is not null;

create table realtime_send_accounting (
    id bigint generated always as identity primary key,
    topic varchar(300) not null,
    reserved_message_cost integer not null check (reserved_message_cost >= 0),
    sent_at timestamptz not null
);
create index idx_realtime_send_accounting_sent_at
    on realtime_send_accounting (sent_at);

create or replace function app_realtime_enqueue_signal(
    p_topic text,
    p_event text,
    p_payload jsonb
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_enabled boolean;
    v_budget bigint;
begin
    if p_topic is null
       or (p_topic not like 'stock:%' and p_topic not like 'inbox:%')
       or length(p_topic) > 300
       or p_event is null
       or length(p_event) > 32
       or p_payload is null
       or octet_length(p_payload::text) > 1024 then
        raise warning 'realtime hint rejected';
        return;
    end if;

    select enabled, remaining_message_budget
      into v_enabled, v_budget
      from public.app_realtime_control
     where id = 1;

    if coalesce(v_enabled, false) and coalesce(v_budget, 0) > 0 then
        insert into public.realtime_signal_outbox (topic, event_name, payload)
        values (p_topic, p_event, p_payload);
    end if;
exception when others then
    raise warning 'realtime hint skipped: %', sqlerrm;
end;
$$;

revoke all on function public.app_realtime_enqueue_signal(text, text, jsonb) from public;
grant execute on function public.app_realtime_enqueue_signal(text, text, jsonb) to current_user;

-- These tables are new and Spring-owned, with no browser consumer yet. Supabase grants anon
-- and authenticated on new public objects through default privileges, so plain public tables
-- must not be assumed private. Guarding on pg_roles keeps plain PostgreSQL usable.
do $$
declare
    v_role text;
begin
    for v_role in
        select rolname from pg_roles where rolname in ('anon', 'authenticated')
    loop
        execute format('revoke all on table public.event_outbox,
                             public.app_realtime_control,
                             public.realtime_token_leases,
                             public.realtime_signal_outbox,
                             public.realtime_send_accounting
            from %I', v_role);
        execute format('revoke execute on function public.app_realtime_enqueue_signal(text, text, jsonb)
            from %I', v_role);
    end loop;
end;
$$;
