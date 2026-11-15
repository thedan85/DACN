-- =========================================================
-- Inventory Realtime stock invalidation triggers
-- (operator-applied, not Flyway)
--
-- Apply after the application migrations as the backend role that owns the
-- application objects:
--   psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 --single-transaction \
--     -f src/main/resources/db/supabase/inventory_realtime.sql
--
-- Run it twice to prove idempotence. It needs V33/V34/V35 only: no Supabase
-- realtime schema, no anon/authenticated roles, and no notification-stage
-- objects. It never sends a message, locks the shared control row, updates the
-- budget, or writes leases/accounting. Those stay foundation-owned.
-- =========================================================

do $$
declare
    v_table text;
    v_columns text[];
    v_column text;
begin
    if to_regclass('public.realtime_signal_outbox') is null then
        raise exception 'Inventory realtime prerequisite not found (public.realtime_signal_outbox missing)';
    end if;
    if not exists (select 1 from public.app_realtime_control where id = 1) then
        raise exception 'Inventory realtime prerequisite not found (public.app_realtime_control row id=1 missing)';
    end if;
    if to_regprocedure('public.app_realtime_enqueue_signal(text, text, jsonb)') is null then
        raise exception 'Inventory realtime prerequisite not found (public.app_realtime_enqueue_signal(text,text,jsonb) missing)';
    end if;
    if not has_function_privilege(current_user,
            'public.app_realtime_enqueue_signal(text, text, jsonb)', 'EXECUTE') then
        raise exception 'Inventory realtime prerequisite not found (EXECUTE on public.app_realtime_enqueue_signal(text,text,jsonb) missing)';
    end if;

    for v_table, v_columns in
        select * from (values
            ('public.products', array['id', 'product_id', 'product_status']),
            ('public.variants', array['product_id', 'variant_stock', 'is_deleted', 'stock_revision'])
        ) as prerequisites(table_name, column_names)
    loop
        if to_regclass(v_table) is null then
            raise exception 'Inventory realtime prerequisite not found (% missing)', v_table;
        end if;
        foreach v_column in array v_columns loop
            if not exists (select 1 from pg_attribute
                            where attrelid = to_regclass(v_table) and attname = v_column
                              and attnum > 0 and not attisdropped) then
                raise exception 'Inventory realtime prerequisite not found (%.% missing)',
                    v_table, v_column;
            end if;
        end loop;
    end loop;
end $$;

-- One shared trigger function. TG_ARGV[0] selects the source rule. The only
-- outbound effect is foundation's best-effort hint enqueue; the exception block
-- keeps the stock/visibility write committed even if the enqueue fails.
create or replace function public.enqueue_stock_signal()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_mode text := tg_argv[0];
    v_product_id text;
    v_parent_status text;
    v_should_enqueue boolean := false;
begin
    if v_mode in ('variant-stock', 'variant-visibility') then
        select p.product_id, p.product_status
          into v_product_id, v_parent_status
          from public.products p
         where p.id = new.product_id;

        if v_parent_status is distinct from 'ACTIVE' then
            return new;
        end if;

        if v_mode = 'variant-stock' then
            v_should_enqueue := not new.is_deleted
                and new.variant_stock is distinct from old.variant_stock;
        else
            v_should_enqueue := new.is_deleted is distinct from old.is_deleted;
        end if;
    elsif v_mode = 'product-visibility' then
        v_product_id := new.product_id;
        v_should_enqueue := new.product_status is distinct from old.product_status
            and (new.product_status = 'ACTIVE' or old.product_status = 'ACTIVE');
    end if;

    if not v_should_enqueue or v_product_id is null then
        return new;
    end if;

    begin
        perform public.app_realtime_enqueue_signal(
            'stock:' || v_product_id,
            'stock.changed',
            jsonb_build_object('productId', v_product_id));
    exception when others then
        -- Never echo the topic or payload here.
        raise warning 'stock signal enqueue skipped: %', sqlstate;
    end;

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
         where p.oid = 'public.enqueue_stock_signal()'::regprocedure
           and a.grantee <> p.proowner
    loop
        execute format('revoke all on function public.enqueue_stock_signal() from %s',
                case when v_grantee = 0 then 'public'
                     else quote_ident(pg_get_userbyid(v_grantee)) end);
    end loop;
end $$;

drop trigger if exists variants_stock_signal on public.variants;
create trigger variants_stock_signal
after update of variant_stock on public.variants
for each row execute function public.enqueue_stock_signal('variant-stock');

drop trigger if exists variants_visibility_signal on public.variants;
create trigger variants_visibility_signal
after update of is_deleted on public.variants
for each row execute function public.enqueue_stock_signal('variant-visibility');

drop trigger if exists products_visibility_signal on public.products;
create trigger products_visibility_signal
after update of product_status on public.products
for each row execute function public.enqueue_stock_signal('product-visibility');
