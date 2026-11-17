-- =========================================================
-- Order-notifications Realtime inbox revision hint trigger
-- (operator-applied, not Flyway)
--
-- Apply after V34 and V37 as the backend role that owns the
-- application objects:
--   psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 --single-transaction \
--     -f src/main/resources/db/supabase/order_notifications_realtime.sql
--
-- Run it twice to prove idempotence. It needs no Supabase realtime schema,
-- no anon/authenticated roles, and no notification_realtime.sql stage.
-- It never sends a message, locks or updates app_realtime_control, or writes
-- leases/accounting. Budget, coalescing, admission, and delivery stay
-- foundation-owned. Install only after users.inbox_revision and foundation's
-- best-effort enqueue function exist.
-- =========================================================

do $$
declare
    v_table text;
    v_columns text[];
    v_column text;
begin
    if to_regclass('public.realtime_signal_outbox') is null then
        raise exception 'Inbox realtime prerequisite not found (public.realtime_signal_outbox missing)';
    end if;
    if not exists (select 1 from public.app_realtime_control where id = 1) then
        raise exception 'Inbox realtime prerequisite not found (app_realtime_control row id=1 missing)';
    end if;
    if to_regprocedure('public.app_realtime_enqueue_signal(text, text, jsonb)') is null then
        raise exception 'Inbox realtime prerequisite not found (app_realtime_enqueue_signal(text,text,jsonb) missing)';
    end if;
    if not has_function_privilege(current_user,
            'public.app_realtime_enqueue_signal(text, text, jsonb)', 'EXECUTE') then
        raise exception 'Inbox realtime prerequisite not found (EXECUTE on public.app_realtime_enqueue_signal(text,text,jsonb) missing)';
    end if;

    for v_table, v_columns in
        select * from (values
            ('public.users', array['id', 'inbox_revision'])
        ) as prerequisites(table_name, column_names)
    loop
        if to_regclass(v_table) is null then
            raise exception 'Inbox realtime prerequisite not found (% missing)', v_table;
        end if;
        foreach v_column in array v_columns loop
            if not exists (select 1 from pg_attribute
                            where attrelid = to_regclass(v_table) and attname = v_column
                              and attnum > 0 and not attisdropped) then
                raise exception 'Inbox realtime prerequisite not found (%.% missing)',
                    v_table, v_column;
            end if;
        end loop;
    end loop;
end $$;

-- One revision change means one private invalidation. The payload is exactly
-- {"revision": <bigint>}, at most 1 KB, and carries no message, count, email,
-- order or user data. Services increment inbox_revision; they never enqueue a
-- second manual hint. The exception block keeps the business write committed
-- even if the enqueue fails. No realtime.send or shared-control lock here.
create or replace function public.enqueue_inbox_revision_signal()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if new.inbox_revision is distinct from old.inbox_revision then
        begin
            perform public.app_realtime_enqueue_signal(
                'inbox:' || new.id::text,
                'inbox.changed',
                jsonb_build_object('revision', new.inbox_revision));
        exception when others then
            -- Never echo the topic or payload here.
            raise warning 'inbox revision hint enqueue skipped: %', sqlstate;
        end;
    end if;
    return new;
end;
$$;

-- CREATE OR REPLACE preserves old ACLs and Supabase default privileges may
-- grant browser roles. Leave only the owner's implicit execution.
do $$
declare
    v_grantee oid;
begin
    for v_grantee in
        select distinct a.grantee
          from pg_proc p
          cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
         where p.oid = 'public.enqueue_inbox_revision_signal()'::regprocedure
           and a.grantee <> p.proowner
    loop
        execute format('revoke all on function public.enqueue_inbox_revision_signal() from %s',
                case when v_grantee = 0 then 'public'
                     else quote_ident(pg_get_userbyid(v_grantee)) end);
    end loop;
end $$;

drop trigger if exists users_inbox_revision_signal on public.users;
create trigger users_inbox_revision_signal
after update of inbox_revision on public.users
for each row execute function public.enqueue_inbox_revision_signal();
