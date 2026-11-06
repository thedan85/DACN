-- =========================================================
-- Supabase Realtime foundation (operator-applied, not Flyway)
--
-- Apply as the backend role after the application migrations:
--   psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 --single-transaction -f src/main/resources/db/supabase/notification_realtime.sql
--
-- Idempotent: run it twice to prove it. It creates no write policy and
-- never sends a message outside the budgeted dispatcher.
-- Stage markers: inventory-realtime adds stock triggers; order-notifications
-- adds the inbox trigger.
-- =========================================================

do $$
declare
    v_table text;
    v_columns text[];
    v_column text;
    v_policy text;
begin
    if to_regclass('public.app_realtime_control') is null then
        raise exception 'Apply the application migrations before this script (app_realtime_control missing)';
    end if;
    if to_regclass('realtime.messages') is null then
        raise exception 'Supabase Realtime schema not found (realtime.messages missing)';
    end if;
    for v_table, v_columns in
        select * from (values
            ('public.users', array['id', 'user_status']),
            ('public.app_realtime_control', array['id', 'enabled', 'remaining_message_budget', 'updated_at']),
            ('public.realtime_token_leases', array['retire_at', 'admitted_product_ids', 'inbox_allowed', 'user_id']),
            ('public.realtime_signal_outbox', array['id', 'topic', 'event_name', 'payload', 'created_at', 'sent_at', 'reserved_message_cost']),
            ('public.realtime_send_accounting', array['topic', 'reserved_message_cost', 'sent_at']),
            ('realtime.messages', array['extension'])
        ) as prerequisites(table_name, column_names)
    loop
        if to_regclass(v_table) is null then
            raise exception 'Realtime prerequisite not found (% missing)', v_table;
        end if;
        foreach v_column in array v_columns loop
            if not exists (select 1 from pg_attribute
                            where attrelid = to_regclass(v_table) and attname = v_column
                              and attnum > 0 and not attisdropped) then
                raise exception 'Realtime prerequisite not found (%.% missing)', v_table, v_column;
            end if;
        end loop;
    end loop;
    if not exists (select 1 from public.app_realtime_control where id = 1) then
        raise exception 'Realtime prerequisite not found (app_realtime_control row id=1 missing)';
    end if;
    if to_regprocedure('realtime.topic()') is null then
        raise exception 'Supabase Realtime prerequisite not found (realtime.topic() missing)';
    end if;
    if to_regprocedure('realtime.send(jsonb,text,text,boolean)') is null then
        raise exception 'Supabase Realtime prerequisite not found (realtime.send(jsonb,text,text,boolean) missing)';
    end if;
    if not exists (select 1 from pg_roles where rolname = 'anon')
       or not exists (select 1 from pg_roles where rolname = 'authenticated') then
        raise exception 'Supabase Realtime prerequisite not found (anon/authenticated roles missing)';
    end if;

    -- Permissive policies combine with OR. Unknown client policies require review,
    -- not silent deletion or an assumption that these two policies override them.
    select p.polname into v_policy
      from pg_policy p
     where p.polrelid = 'realtime.messages'::regclass
       and p.polpermissive
       and p.polname not in ('dacn realtime stock receive', 'dacn realtime inbox receive')
       and exists (
           select 1 from unnest(p.polroles) as r(role_id)
            where case when r.role_id = 0 then true
                       else pg_has_role('anon', r.role_id, 'USAGE')
                         or pg_has_role('authenticated', r.role_id, 'USAGE') end)
     order by p.polname
     limit 1;
    if v_policy is not null then
        raise exception 'Conflicting Realtime policy: %. Review existing client access before rollout', v_policy;
    end if;
end $$;

create schema if not exists app_private;

-- An inbox topic is readable only while the signed account exists and is active.
create or replace function app_private.realtime_inbox_allowed(p_user_id text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select p_user_id is not null
       and exists (
           select 1
             from public.users u
            where u.id::text = p_user_id
              and u.user_status = 'ACTIVE'
       );
$$;

revoke all on function app_private.realtime_inbox_allowed(text) from public;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'grant usage on schema app_private to authenticated';
        execute 'grant execute on function app_private.realtime_inbox_allowed(text) to authenticated';
    end if;
end $$;

-- Stock broadcast receive: only topics in the signed stock_product_ids claim.
drop policy if exists "dacn realtime stock receive" on realtime.messages;
create policy "dacn realtime stock receive"
on realtime.messages
for select
to anon, authenticated
using (
    extension = 'broadcast'
    and realtime.topic() like 'stock:%'
    and exists (
        select 1
          from jsonb_array_elements_text(
                   coalesce(
                       nullif(current_setting('request.jwt.claims', true), '')::jsonb
                           -> 'stock_product_ids',
                       '[]'::jsonb)
               ) as admitted(product_id)
         where 'stock:' || admitted.product_id = realtime.topic()
    )
);

-- Inbox broadcast receive: own topic, opt-in claim, active account only.
drop policy if exists "dacn realtime inbox receive" on realtime.messages;
create policy "dacn realtime inbox receive"
on realtime.messages
for select
to authenticated
using (
    extension = 'broadcast'
    and coalesce(
            (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'inbox_allowed')::boolean,
            false)
    and realtime.topic() = 'inbox:' || (
            nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'app_user_id')
    and app_private.realtime_inbox_allowed(
            nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'app_user_id')
);

-- The only path from the database to Realtime Broadcast. Private server-side
-- sends skip receive authorization; clients can never call this.
create or replace function app_private.send_realtime_signal(
    p_topic text, p_event text, p_payload jsonb
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_payload jsonb;
    v_product_id text;
    v_revision text;
begin
    if p_topic is null or p_event is null or jsonb_typeof(p_payload) is distinct from 'object' then
        raise exception 'Invalid Realtime signal' using errcode = '22023';
    end if;

    if p_event = 'stock.changed' and p_topic like 'stock:%' then
        v_product_id := substr(p_topic, 7);
        if v_product_id ~ '^[[:space:]]*$' or length(v_product_id) > 255
           or jsonb_typeof(p_payload -> 'productId') is distinct from 'string'
           or (p_payload ->> 'productId') is distinct from v_product_id then
            raise exception 'Invalid Realtime stock signal' using errcode = '22023';
        end if;
        v_payload := jsonb_build_object('productId', v_product_id);
    elsif p_event = 'inbox.changed' and p_topic ~ '^inbox:[1-9][0-9]{0,18}$' then
        v_revision := p_payload ->> 'revision';
        if substr(p_topic, 7)::numeric > 9223372036854775807
           or jsonb_typeof(p_payload -> 'revision') is distinct from 'number'
           or v_revision !~ '^[0-9]+$' or length(v_revision) > 19 then
            raise exception 'Invalid Realtime inbox signal' using errcode = '22023';
        end if;
        if v_revision::numeric > 9223372036854775807 then
            raise exception 'Invalid Realtime inbox revision' using errcode = '22023';
        end if;
        v_payload := jsonb_build_object('revision', v_revision::bigint);
    else
        raise exception 'Invalid Realtime topic or event' using errcode = '22023';
    end if;

    -- Only these reconstructed invalidations cross the outbound trust boundary.
    if octet_length(convert_to(v_payload::text, 'UTF8')) > 1024 then
        raise exception 'Realtime signal exceeds 1024 UTF-8 bytes' using errcode = '22023';
    end if;
    perform realtime.send(v_payload, p_event, p_topic, true);
end;
$$;

revoke all on function app_private.send_realtime_signal(text, text, jsonb) from public;

-- Coalesce pending hints, reserve one rolling budget, and send at most one
-- message per topic. Everything happens in the caller's single transaction.
create or replace function app_private.dispatch_realtime_signals(
    p_now timestamptz default null
) returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_enabled boolean;
    v_budget bigint;
    v_now timestamptz;
    v_watermark bigint;
    v_hint_ids bigint[];
    v_sent integer := 0;
    v_topic record;
    v_recipients integer;
    v_cost integer;
    v_window integer;
begin
    select enabled, remaining_message_budget
      into v_enabled, v_budget
       from public.app_realtime_control
      where id = 1
        for update;

    -- Default-expression time would precede a lock wait and misdate reservations.
    v_now := coalesce(p_now, clock_timestamp());

    select max(batch.id), array_agg(batch.id)
      into v_watermark, v_hint_ids
      from (select h.id
              from public.realtime_signal_outbox h
              where h.sent_at is null
              order by h.id
              limit 100
              for update skip locked) batch;

    if v_watermark is null then
        return 0;
    end if;

    -- Hints older than 30 seconds are stale; periodic REST reconciliation is
    -- the safety net. They never consume budget.
    update public.realtime_signal_outbox
       set sent_at = v_now,
           reserved_message_cost = 0
     where sent_at is null
       and id = any(v_hint_ids)
       and created_at <= v_now - interval '30 seconds';

    if not coalesce(v_enabled, false) or coalesce(v_budget, 0) <= 0 then
        return 0;
    end if;

    for v_topic in
        with batch as (
            select h.id, h.topic, h.event_name, h.payload,
                   row_number() over (
                       partition by h.topic
                       order by case
                                    when h.topic like 'inbox:%'
                                    then coalesce((h.payload ->> 'revision')::bigint, 0)
                                    else h.id
                                end desc,
                                h.id desc
                   ) as rank
              from public.realtime_signal_outbox h
              where h.sent_at is null
                and h.id <= v_watermark
                and h.id = any(v_hint_ids)
        )
        select topic, event_name, payload, id as chosen_id
          from batch
         where rank = 1
         order by topic
    loop
        if v_topic.topic like 'stock:%' then
            select count(*)
              into v_recipients
              from public.realtime_token_leases l
              where l.retire_at > v_now
               and l.admitted_product_ids @> jsonb_build_array(substr(v_topic.topic, 7));
        else
            select count(*)
              into v_recipients
              from public.realtime_token_leases l
              where l.retire_at > v_now
               and l.inbox_allowed
               and l.user_id::text = substr(v_topic.topic, 7);
        end if;

        if v_recipients = 0 then
            update public.realtime_signal_outbox
               set sent_at = v_now, reserved_message_cost = 0
             where topic = v_topic.topic
               and sent_at is null
               and id <= v_watermark
               and id = any(v_hint_ids);
            continue;
        end if;

        -- One send plus the conservative receiver count: Free-plan billing.
        v_cost := 1 + v_recipients;

        select coalesce(sum(a.reserved_message_cost), 0)
          into v_window
          from public.realtime_send_accounting a
          where a.sent_at > v_now - interval '1 second'
            and a.sent_at <= v_now;

        -- Never split one topic across windows; leave it pending instead.
        if v_window + v_cost > 60 or v_cost > v_budget then
            continue;
        end if;

        update public.app_realtime_control
           set remaining_message_budget = remaining_message_budget - v_cost,
               updated_at = v_now
         where id = 1;
        v_budget := v_budget - v_cost;

        insert into public.realtime_send_accounting (topic, reserved_message_cost, sent_at)
        values (v_topic.topic, v_cost, v_now);

        perform app_private.send_realtime_signal(
                v_topic.topic, v_topic.event_name, v_topic.payload);

        -- Mark only this tick's captured hints; anything newer stays pending.
        update public.realtime_signal_outbox
           set sent_at = v_now,
               reserved_message_cost = case when id = v_topic.chosen_id then v_cost else 0 end
         where topic = v_topic.topic
           and sent_at is null
           and id <= v_watermark
           and id = any(v_hint_ids);

        v_sent := v_sent + 1;
    end loop;

    return v_sent;
end;
$$;

revoke all on function app_private.dispatch_realtime_signals(timestamptz) from public;

-- CREATE OR REPLACE preserves old ACLs, and default privileges can grant more
-- than PUBLIC. Restrict only these application-owned functions, not role membership
-- or the operator's default privileges for unrelated objects.
do $$
declare
    v_function record;
    v_grantee oid;
begin
    for v_function in
        select p.oid::regprocedure as signature
          from pg_proc p
         where p.oid in ('app_private.realtime_inbox_allowed(text)'::regprocedure,
                         'app_private.send_realtime_signal(text,text,jsonb)'::regprocedure,
                         'app_private.dispatch_realtime_signals(timestamptz)'::regprocedure)
    loop
        for v_grantee in
            select distinct a.grantee
              from pg_proc p
              cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
             where p.oid = v_function.signature::oid and a.grantee <> p.proowner
        loop
            execute format('revoke all on function %s from %s', v_function.signature,
                    case when v_grantee = 0 then 'public'
                         else quote_ident(pg_get_userbyid(v_grantee)) end);
        end loop;
    end loop;
end $$;

grant execute on function app_private.realtime_inbox_allowed(text) to authenticated;
grant execute on function app_private.dispatch_realtime_signals(timestamptz) to current_user;
grant execute on function app_private.send_realtime_signal(text, text, jsonb) to current_user;

do $$
begin
    if has_function_privilege('anon', 'app_private.realtime_inbox_allowed(text)', 'EXECUTE')
       or has_function_privilege('anon', 'app_private.send_realtime_signal(text,text,jsonb)', 'EXECUTE')
       or has_function_privilege('authenticated', 'app_private.send_realtime_signal(text,text,jsonb)', 'EXECUTE')
       or has_function_privilege('anon', 'app_private.dispatch_realtime_signals(timestamptz)', 'EXECUTE')
       or has_function_privilege('authenticated', 'app_private.dispatch_realtime_signals(timestamptz)', 'EXECUTE') then
        raise exception 'Unsafe inherited Realtime function privileges; review browser role membership before rollout';
    end if;
end $$;
