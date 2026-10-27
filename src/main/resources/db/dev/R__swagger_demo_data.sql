-- Dev-only Swagger demo accounts/data.
-- Loaded by application-dev.yml from classpath:db/dev.
-- Never enable this location in production: the password is intentionally known.

INSERT INTO users (user_id, user_email, user_password_hash, user_status, role_id)
VALUES
    ('USR_swagger-admin-example-com', 'swagger.admin@example.com',
     '$2a$10$Voiarkr4D1arVGIeixNlZOAJ31JcbSKrH5rrQPN26BAK6nMKQXFxu', 'ACTIVE',
     (SELECT id FROM roles WHERE lower(role_code) = 'admin')),
    ('USR_swagger-employee-example-com', 'swagger.employee@example.com',
     '$2a$10$Voiarkr4D1arVGIeixNlZOAJ31JcbSKrH5rrQPN26BAK6nMKQXFxu', 'ACTIVE',
     (SELECT id FROM roles WHERE lower(role_code) = 'employee')),
    ('USR_swagger-customer-example-com', 'swagger.customer@example.com',
     '$2a$10$Voiarkr4D1arVGIeixNlZOAJ31JcbSKrH5rrQPN26BAK6nMKQXFxu', 'ACTIVE',
     (SELECT id FROM roles WHERE lower(role_code) = 'customer'))
ON CONFLICT (user_id) DO UPDATE SET
    user_email = EXCLUDED.user_email,
    user_password_hash = EXCLUDED.user_password_hash,
    user_status = EXCLUDED.user_status,
    role_id = EXCLUDED.role_id,
    updated_at = now();

INSERT INTO admins (admin_id, user_id, admin_name, admin_status)
SELECT 'ADM_swagger-admin-example-com', u.id, 'Swagger Admin', 'ACTIVE'
FROM users u WHERE u.user_id = 'USR_swagger-admin-example-com'
ON CONFLICT (user_id) DO UPDATE SET
    admin_id = EXCLUDED.admin_id,
    admin_name = EXCLUDED.admin_name,
    admin_status = EXCLUDED.admin_status;

INSERT INTO employees (employee_id, user_id, employee_name, employee_address,
                       employee_phone, employee_status)
SELECT 'EMP_swagger-employee-example-com', u.id, 'Swagger Employee',
       '123 Demo St, HCMC', '0901234567', 'ACTIVE'
FROM users u WHERE u.user_id = 'USR_swagger-employee-example-com'
ON CONFLICT (user_id) DO UPDATE SET
    employee_id = EXCLUDED.employee_id,
    employee_name = EXCLUDED.employee_name,
    employee_address = EXCLUDED.employee_address,
    employee_phone = EXCLUDED.employee_phone,
    employee_status = EXCLUDED.employee_status;

INSERT INTO customers (customer_id, user_id, customer_name, customer_address, customer_phone)
SELECT 'CUS_swagger-customer-example-com', u.id, 'Swagger Customer',
       '123 Demo St, HCMC', '0901234567'
FROM users u WHERE u.user_id = 'USR_swagger-customer-example-com'
ON CONFLICT (user_id) DO UPDATE SET
    customer_id = EXCLUDED.customer_id,
    customer_name = EXCLUDED.customer_name,
    customer_address = EXCLUDED.customer_address,
    customer_phone = EXCLUDED.customer_phone;

INSERT INTO carts (cart_id, customer_id)
SELECT 'CART_CUS_swagger-customer-example-com', c.id
FROM customers c WHERE c.customer_id = 'CUS_swagger-customer-example-com'
ON CONFLICT (customer_id) DO NOTHING;

INSERT INTO cart_items (cart_item_id, cart_id, variant_id, quantity)
SELECT 'CI_SWAGGER_EMBER', c.id, v.id, 2
FROM carts c CROSS JOIN variants v
WHERE c.cart_id = 'CART_CUS_swagger-customer-example-com'
  AND v.variant_id = 'ember-tetra-default'
ON CONFLICT (cart_id, variant_id) DO UPDATE SET
    cart_item_id = EXCLUDED.cart_item_id,
    quantity = EXCLUDED.quantity;

INSERT INTO cart_items (cart_item_id, cart_id, variant_id, quantity)
SELECT 'CI_SWAGGER_NEON', c.id, v.id, 1
FROM carts c CROSS JOIN variants v
WHERE c.cart_id = 'CART_CUS_swagger-customer-example-com'
  AND v.variant_id = 'neon-tetra-default'
ON CONFLICT (cart_id, variant_id) DO UPDATE SET
    cart_item_id = EXCLUDED.cart_item_id,
    quantity = EXCLUDED.quantity;

INSERT INTO orders (order_id, customer_id, order_status, order_total,
                    order_address, order_recipient_name, order_phone)
SELECT 'ORD_SWAGGER_DEMO', c.id, 'PENDING', 30000.00,
       '123 Demo St, HCMC', 'Swagger Customer', '0901234567'
FROM customers c WHERE c.customer_id = 'CUS_swagger-customer-example-com'
ON CONFLICT (order_id) DO NOTHING;

INSERT INTO order_items (order_id, variant_id, quantity, unit_price)
SELECT o.id, v.id, 2, 15000.00
FROM orders o CROSS JOIN variants v
WHERE o.order_id = 'ORD_SWAGGER_DEMO'
  AND v.variant_id = 'ember-tetra-default'
  AND NOT EXISTS (
      SELECT 1 FROM order_items oi
      WHERE oi.order_id = o.id AND oi.variant_id = v.id
  );

INSERT INTO payments (payment_id, order_id, payment_method, payment_status, payment_amount)
SELECT 'PAY_SWAGGER_DEMO', o.id, 'COD', 'PENDING', 30000.00
FROM orders o WHERE o.order_id = 'ORD_SWAGGER_DEMO'
ON CONFLICT (order_id) DO NOTHING;
