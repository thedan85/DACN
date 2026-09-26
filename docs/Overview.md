Project: Enterprise Headless E-Commerce Platform with Touchless Fulfillment and PIM

## Tech stack:
    - Frontend: Next.js
    - Backend: Spring Boot(Spring Security, Spring Data JPA), Refresh + Access token, PostgreSQL / Supabase, Redis, RabbitMQ
    - Testing: JUnit, Mockito, Spring Test, Cypress
    - Media: Cloudinary
    - Architecture: headless MVC
    - Development: Github, Notion, Google Docs

## Functional Requirements

1. **Product Information Management (PIM) and Multi-Variant Catalog**
    - Manage products with flexible attributes and configuration.
    - Track inventory independently for each product variant.
    - Support organized category management and consistent product information across all sales channels.

2. **High-Performance Storefront Experience**
    - Provide a fast storefront optimized with server-side rendering (SSR) and static site generation (SSG) where appropriate.
    - Support multi-criteria product filtering and sorting.
    - Keep shopping carts synchronized between the client and server.
    - Provide persistent wishlists for customers.

3. **Order Processing and Touchless Fulfillment**
    - Support the complete order workflow.
    - Automatically submit orders to shipping providers through GHTK and GHN sandbox integrations.
    - Track shipment status in real time through webhook callbacks from shipping providers.

4. **Flexible Digital Payments**
    - Integrate online payment gateways such as VNPAY and MoMo in sandbox environments.
    - Support dynamic VietQR payments.
    - Reconcile payment results instantly and update order payment status automatically.

## WBS-Aligned Expected Features

### 1.1 Account Management

- Log in and log out.
- Create customer accounts.
- Search for accounts.
- Edit account information.
- Lock or unlock accounts.

### 1.2 Product Management

- Add products and product variants.
- Edit product information.
- Search for products.
- Lock or unlock products.
- Classify products by category.
- Filter products by attributes and other criteria.

### 1.3 Product Category Management

- Add product categories.
- Edit category information.
- Search for categories.
- Lock or unlock categories.
- Delete product categories when they are no longer in use.

### 1.4 Order Management

- Place orders.
- Receive and process orders.
- Cancel orders.
- Request and process order returns.
- Review completed orders.
- Search for orders.
- Pay for orders through supported payment methods.
- Assign orders to delivery providers.
- Track order status and delivery progress.

### 1.5 Notification Management

- Send system and order notifications.
- Receive notifications.
- Delete notifications.

### 1.6 Shopping Cart Management

- Add products and variants to the shopping cart.
- Remove products and variants from the shopping cart.
- Update cart quantities and synchronize cart contents across client and server sessions.

### 1.7 Statistics and Reporting

- View revenue statistics over time.
- View the number of users.
- View the number of orders.
- View inventory quantity statistics.

### 1.8 Permission Management

- Add permissions and roles.
- Edit permissions and role assignments.
- Delete permissions when they are no longer required.