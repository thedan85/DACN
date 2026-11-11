alter table orders add column shipping_province varchar(255);
alter table orders add column shipping_ward varchar(255);
alter table orders add column ghn_order_code varchar(50);
create unique index ux_orders_ghn_order_code on orders (ghn_order_code);
