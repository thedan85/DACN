# Hợp đồng API thật: Backend đối chiếu Frontend

## Phạm vi và quy ước

Báo cáo này được lập từ mã nguồn backend hiện tại, không sửa mã nguồn ứng dụng. Prefix đầy đủ của backend là `/api/v1`. Các method có `Pageable` nhận query `page`, `size` và tùy chọn `sort`; `page` là zero-based, mặc định `page=0`, `size=20`, giới hạn `size<=100` ([WebConfig.java](src/main/java/com/project/dacn/config/WebConfig.java), `pageableCustomizer`, dòng 12-18).

`ApiResponse` có dạng `{code, message, data}` ([ApiResponse.java](src/main/java/com/project/dacn/common/response/ApiResponse.java), dòng 8-27). Kết quả phân trang có dạng `{content, page, size, totalElements, totalPages}` ([PageResponse.java](src/main/java/com/project/dacn/common/response/PageResponse.java), dòng 11-29).

## 1. Hợp đồng endpoint backend

Quyền trong bảng là quyền thực thi. `PUBLIC` là không cần access token; `AUTH` là cần đăng nhập; tên dạng `permission:*` là authority cụ thể. Ngoài `@PreAuthorize`, các rule URL trong [SecurityConfig.java](src/main/java/com/project/dacn/config/SecurityConfig.java) cũng được áp dụng.

| Method | Đường dẫn đầy đủ | Query/path/body chính | Quyền | Nguồn |
|---|---|---|---|---|
| POST | `/api/v1/auth/register` | body `email,password,name,phone` | PUBLIC | [AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):46 |
| POST | `/api/v1/auth/login` | body `email,password` | PUBLIC | [AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):57 |
| POST | `/api/v1/auth/google` | body `idToken` | PUBLIC | [AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):75 |
| POST | `/api/v1/auth/refresh` | cookie `refresh_token`; không có body | PUBLIC + trusted origin | [AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):96 |
| POST | `/api/v1/auth/logout` | cookie `refresh_token` tùy chọn; không có body | PUBLIC + trusted origin | [AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):115 |
| GET | `/api/v1/auth/me` | không có | AUTH | [AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):131 |
| PATCH | `/api/v1/auth/me` | body `name,phone,address` | AUTH | [AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):137 |
| POST | `/api/v1/auth/change-password` | body `currentPassword,newPassword`; cookie `refresh_token` tùy chọn | AUTH | [AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):143 |
| GET | `/api/v1/health/redis` | không có | PUBLIC | [HealthController.java](src/main/java/com/project/dacn/controller/HealthController.java):12 |
| GET | `/api/v1/products` | `keyword,category_id,min_price,max_price,status,sort,page,size` | PUBLIC | [ProductController.java](src/main/java/com/project/dacn/controller/catalog/ProductController.java):39 |
| GET | `/api/v1/products/search` | `name,status,categoryId,page,size` | PUBLIC | [ProductController.java](src/main/java/com/project/dacn/controller/catalog/ProductController.java):54 |
| GET | `/api/v1/products/{slug}` | path `slug`; review `page,size,sort` | PUBLIC | [ProductController.java](src/main/java/com/project/dacn/controller/catalog/ProductController.java):65 |
| POST | `/api/v1/products` | body `ProductCreateRequest` | `product:create` | [ProductController.java](src/main/java/com/project/dacn/controller/catalog/ProductController.java):83 |
| PUT | `/api/v1/products/{productId}` | body `ProductUpdateRequest` | `product:update` | [ProductController.java](src/main/java/com/project/dacn/controller/catalog/ProductController.java):90 |
| PATCH | `/api/v1/products/{productId}` | body `ProductUpdateRequest` | `product:update` | [ProductController.java](src/main/java/com/project/dacn/controller/catalog/ProductController.java):97 |
| DELETE | `/api/v1/products/{productId}` | path `productId` | `product:delete` | [ProductController.java](src/main/java/com/project/dacn/controller/catalog/ProductController.java):104 |
| GET | `/api/v1/variants` | `page,size,sort` | PUBLIC | [VariantController.java](src/main/java/com/project/dacn/controller/catalog/VariantController.java):32 |
| GET | `/api/v1/variants/{variantId}` | path `variantId` | PUBLIC | [VariantController.java](src/main/java/com/project/dacn/controller/catalog/VariantController.java):38 |
| GET | `/api/v1/products/{slug}/variants` | path `slug`; `page,size,sort` | PUBLIC | [VariantController.java](src/main/java/com/project/dacn/controller/catalog/VariantController.java):43 |
| POST | `/api/v1/variants` | body `VariantCreateRequest` | `variant:create` | [VariantController.java](src/main/java/com/project/dacn/controller/catalog/VariantController.java):50 |
| PUT | `/api/v1/variants/{variantId}` | body `VariantUpdateRequest` | `variant:update` | [VariantController.java](src/main/java/com/project/dacn/controller/catalog/VariantController.java):56 |
| PATCH | `/api/v1/variants/{variantId}` | body `VariantUpdateRequest` | `variant:update` | [VariantController.java](src/main/java/com/project/dacn/controller/catalog/VariantController.java):63 |
| DELETE | `/api/v1/variants/{variantId}` | path `variantId` | `variant:delete` | [VariantController.java](src/main/java/com/project/dacn/controller/catalog/VariantController.java):70 |
| GET | `/api/v1/categories` | `page,size,sort` | PUBLIC | [CategoryController.java](src/main/java/com/project/dacn/controller/catalog/CategoryController.java):32 |
| GET | `/api/v1/categories/{categoryId}` | path `categoryId` | PUBLIC | [CategoryController.java](src/main/java/com/project/dacn/controller/catalog/CategoryController.java):37 |
| POST | `/api/v1/categories` | body `CategoryCreateRequest` | `category:create` | [CategoryController.java](src/main/java/com/project/dacn/controller/catalog/CategoryController.java):42 |
| PUT/PATCH/DELETE | `/api/v1/categories/{categoryId}` | body `CategoryUpdateRequest` cho PUT/PATCH | lần lượt `category:update`, `category:update`, `category:delete` | [CategoryController.java](src/main/java/com/project/dacn/controller/catalog/CategoryController.java):48-64 |
| GET | `/api/v1/attributes` | `page,size,sort` | PUBLIC | [AttributeController.java](src/main/java/com/project/dacn/controller/catalog/AttributeController.java):32 |
| GET | `/api/v1/attributes/{attributeId}` | path `attributeId` | PUBLIC | [AttributeController.java](src/main/java/com/project/dacn/controller/catalog/AttributeController.java):37 |
| POST/PUT/PATCH/DELETE | `/api/v1/attributes[/{attributeId}]` | body `AttributeCreateRequest` hoặc `AttributeUpdateRequest` | lần lượt `attribute:create`, `attribute:update`, `attribute:update`, `attribute:delete` | [AttributeController.java](src/main/java/com/project/dacn/controller/catalog/AttributeController.java):42-64 |
| GET | `/api/v1/attribute-values` | `page,size,sort` | PUBLIC | [AttributeValueController.java](src/main/java/com/project/dacn/controller/catalog/AttributeValueController.java):29 |
| GET | `/api/v1/attribute-values/{attributeValueId}` | path `attributeValueId` | PUBLIC | [AttributeValueController.java](src/main/java/com/project/dacn/controller/catalog/AttributeValueController.java):34 |
| GET | `/api/v1/attributes/{attributeId}/values` | path `attributeId`; `page,size,sort` | PUBLIC | [AttributeValueController.java](src/main/java/com/project/dacn/controller/catalog/AttributeValueController.java):39 |
| POST/PUT/PATCH/DELETE | `/api/v1/attribute-values[/{attributeValueId}]` | body create/update DTO tương ứng | lần lượt `attribute:create`, `attribute:update`, `attribute:update`, `attribute:delete` | [AttributeValueController.java](src/main/java/com/project/dacn/controller/catalog/AttributeValueController.java):46-68 |
| GET | `/api/v1/products/{productId}/attributes` | path `productId`; `page,size,sort` | PUBLIC | [ProductAttributeController.java](src/main/java/com/project/dacn/controller/catalog/ProductAttributeController.java):27 |
| GET | `/api/v1/product-attributes/{productValueId}` | path `productValueId` | PUBLIC | [ProductAttributeController.java](src/main/java/com/project/dacn/controller/catalog/ProductAttributeController.java):33 |
| POST | `/api/v1/product-attributes` | body `ProductAttributeValueCreateRequest` | `attribute:create` | [ProductAttributeController.java](src/main/java/com/project/dacn/controller/catalog/ProductAttributeController.java):38 |
| DELETE | `/api/v1/product-attributes/{productValueId}` | path `productValueId` | `attribute:delete` | [ProductAttributeController.java](src/main/java/com/project/dacn/controller/catalog/ProductAttributeController.java):45 |
| GET | `/api/v1/variants/{variantId}/attributes` | path `variantId`; `page,size,sort` | PUBLIC | [VariantAttributeController.java](src/main/java/com/project/dacn/controller/catalog/VariantAttributeController.java):27 |
| GET | `/api/v1/variant-attributes/{variantValueId}` | path `variantValueId` | PUBLIC | [VariantAttributeController.java](src/main/java/com/project/dacn/controller/catalog/VariantAttributeController.java):33 |
| POST | `/api/v1/variant-attributes` | body `VariantAttributeValueCreateRequest` | `variant:create` | [VariantAttributeController.java](src/main/java/com/project/dacn/controller/catalog/VariantAttributeController.java):38 |
| DELETE | `/api/v1/variant-attributes/{variantValueId}` | path `variantValueId` | `variant:delete` | [VariantAttributeController.java](src/main/java/com/project/dacn/controller/catalog/VariantAttributeController.java):45 |
| GET | `/api/v1/media`, `/api/v1/media/{mediaId}` | `page,size,sort` hoặc path `mediaId` | PUBLIC | [MediaController.java](src/main/java/com/project/dacn/controller/catalog/MediaController.java):31-43 |
| GET | `/api/v1/products/{productId}/media`, `/api/v1/variants/{variantId}/media` | path + `page,size,sort` | PUBLIC | [MediaController.java](src/main/java/com/project/dacn/controller/catalog/MediaController.java):48-60 |
| POST/PUT/PATCH/DELETE | `/api/v1/media[/{mediaId}]` | body `MediaCreateRequest` hoặc `MediaUpdateRequest` | lần lượt `media:create`, `media:update`, `media:update`, `media:delete` | [MediaController.java](src/main/java/com/project/dacn/controller/catalog/MediaController.java):65-88 |
| POST | `/api/v1/uploads/products/{productId}/thumbnail` | multipart field `file` | `media:create` | [UploadController.java](src/main/java/com/project/dacn/controller/catalog/UploadController.java):25 |
| POST | `/api/v1/uploads/variants/{variantId}` | multipart field `file` | `media:create` | [UploadController.java](src/main/java/com/project/dacn/controller/catalog/UploadController.java):32 |
| GET | `/api/v1/cart` | không có | AUTH | [CartController.java](src/main/java/com/project/dacn/controller/cart/CartController.java):33 |
| DELETE | `/api/v1/cart` | không có | AUTH | [CartController.java](src/main/java/com/project/dacn/controller/cart/CartController.java):38 |
| POST | `/api/v1/cart/items` | body `variantId,quantity` | AUTH | [CartController.java](src/main/java/com/project/dacn/controller/cart/CartController.java):44 |
| PATCH | `/api/v1/cart/items/{cartItemId}` | path `cartItemId`; body `quantity` | AUTH | [CartController.java](src/main/java/com/project/dacn/controller/cart/CartController.java):52 |
| DELETE | `/api/v1/cart/items/{cartItemId}` | path `cartItemId` | AUTH | [CartController.java](src/main/java/com/project/dacn/controller/cart/CartController.java):61 |
| POST | `/api/v1/cart/checkout` | body `address,recipientName,phone,method` | AUTH | [CartController.java](src/main/java/com/project/dacn/controller/cart/CartController.java):67 |
| GET | `/api/v1/orders` | `status,q,createdAfter,page,size,sort` | AUTH | [OrderController.java](src/main/java/com/project/dacn/controller/order/OrderController.java):35 |
| GET | `/api/v1/orders/{orderId}` | path `orderId` | AUTH | [OrderController.java](src/main/java/com/project/dacn/controller/order/OrderController.java):48 |
| POST | `/api/v1/payments/vnpay/{orderId}` | path `orderId` | AUTH | [PaymentController.java](src/main/java/com/project/dacn/controller/payment/PaymentController.java):29 |
| GET | `/api/v1/payments/orders/{orderId}` | path `orderId` | AUTH | [PaymentController.java](src/main/java/com/project/dacn/controller/payment/PaymentController.java):38 |
| POST | `/api/v1/variants/{variantId}/reviews` | path `variantId`; body `ReviewCreateRequest` | AUTH | [ReviewController.java](src/main/java/com/project/dacn/controller/engagement/ReviewController.java):32 |
| PATCH | `/api/v1/reviews/{reviewId}` | path `reviewId`; body `ReviewUpdateRequest` | AUTH | [ReviewController.java](src/main/java/com/project/dacn/controller/engagement/ReviewController.java):41 |
| DELETE | `/api/v1/reviews/{reviewId}` | path `reviewId` | AUTH | [ReviewController.java](src/main/java/com/project/dacn/controller/engagement/ReviewController.java):51 |
| GET | `/api/v1/orders/{orderId}/reviews` | path `orderId`; `page,size,sort` | AUTH | [ReviewController.java](src/main/java/com/project/dacn/controller/engagement/ReviewController.java):57 |
| GET | `/api/v1/variants/{variantId}/reviews` | path `variantId`; `page,size,sort` | AUTH | [ReviewController.java](src/main/java/com/project/dacn/controller/engagement/ReviewController.java):75 |
| GET | `/api/v1/staff/orders` | `status,q,page,size,sort` | `order:read` | [StaffOrderController.java](src/main/java/com/project/dacn/controller/order/StaffOrderController.java):32 |
| PATCH | `/api/v1/staff/orders/{orderId}/status` | path `orderId`; body `status` | `order:update` | [StaffOrderController.java](src/main/java/com/project/dacn/controller/order/StaffOrderController.java):46 |
| POST | `/api/v1/admin/orders/{orderId}/complete` | path `orderId` | `order:complete` | [AdminOrderController.java](src/main/java/com/project/dacn/controller/order/AdminOrderController.java):28 |

Ngoài ra có các endpoint không nằm trong bảng FE đã cung cấp: webhook `POST /api/v1/webhooks/vnpay/**` và `POST /api/v1/webhooks/momo/**` (PUBLIC theo rule webhook, chữ ký được controller/service xác minh), cùng các endpoint review/staff/admin ở trên.

## 2. Security, CORS và WebConfig

- CORS đọc `app.cors.allowed-origins`, mặc định `http://localhost:3000`; giá trị được tách bằng dấu phẩy và trim ([CorsConfig.java](src/main/java/com/project/dacn/config/CorsConfig.java):18-23). Vì vậy origin thực tế phụ thuộc `CORS_ALLOWED_ORIGINS` khi chạy production, không thể suy ra thêm từ source.
- Cho phép method `GET, POST, PUT, PATCH, DELETE, OPTIONS`, mọi request header (`*`), và `allowCredentials=true` ([CorsConfig.java](src/main/java/com/project/dacn/config/CorsConfig.java):27-31).
- PUBLIC trong SecurityConfig: OPTIONS; `/error`, static assets, Swagger/OpenAPI, actuator/health và `/api/v1/health/**`; `/api/v1/webhooks/**`; POST register/login/google/refresh/logout ([SecurityConfig.java](src/main/java/com/project/dacn/config/SecurityConfig.java):43-59).
- GET catalog (`products`, `variants`, `categories`, `attributes`, `attribute-values`, `product-attributes`, `variant-attributes`, `media`) là PUBLIC ([SecurityConfig.java](src/main/java/com/project/dacn/config/SecurityConfig.java):61-67).
- Các write catalog/media/upload bị chặn theo authority ở URL và thường lặp lại bằng `@PreAuthorize`; cart, customer orders, payment và các auth endpoint còn lại đi qua `.anyRequest().authenticated()` ([SecurityConfig.java](src/main/java/com/project/dacn/config/SecurityConfig.java):69-123).
- WebConfig đặt fallback page `0,20` và max page size `100`; không đổi tên tham số khỏi chuẩn Spring `page,size` ([WebConfig.java](src/main/java/com/project/dacn/config/WebConfig.java):12-18).

## 3. Đối chiếu từng dòng FE

Đánh giá dưới đây lấy base FE `/api/v1` như bảng yêu cầu. `KHỚP` nghĩa path và method có handler; phần ghi chú vẫn nêu contract body/query/quyền cần giữ đúng.

| FE gọi | Kết quả | Đối chiếu backend |
|---|---|---|
| POST `auth/login` | KHỚP | `/api/v1/auth/login`, body `email,password`, PUBLIC. |
| POST `auth/google` | KHỚP | Body backend là `idToken`. |
| POST `auth/register` | KHỚP | Body `email,password,name,phone`. |
| POST `auth/refresh` | KHỚP | Path/method đúng; phải gửi cookie credentials, không gửi body refresh token. |
| GET `auth/me` | KHỚP | AUTH; response thật nêu ở mục 4. |
| PATCH `auth/me` | KHỚP | Body đúng tên `name,phone,address`; cả ba field `name,phone` bắt buộc. |
| POST `auth/change-password` | KHỚP | Body `currentPassword,newPassword`; AUTH. |
| POST `auth/logout` | KHỚP | Cookie tùy chọn, không có body; PUBLIC + trusted origin. |
| GET `products?page&size` + filter/sort | KHỚP có điều kiện | Path/method/paging đúng. Tên filter backend là `keyword`, `category_id`, `min_price`, `max_price`, `status`, `sort`; không dùng `limit`. |
| GET `products/{slug}` + review paging | KHỚP | Path đúng; review paging là `page,size,sort`. |
| GET `categories?page&size` | KHỚP | PUBLIC, Spring paging `page,size`. |
| GET `cart` | KHỚP | AUTH. |
| POST `cart/items` | KHỚP | Body phải `variantId,quantity`; AUTH. |
| PATCH `cart/items/{cartItemId}` | KHỚP | Body `quantity`; AUTH. |
| DELETE `cart/items/{cartItemId}` | KHỚP | AUTH. |
| POST `cart/checkout` | KHỚP | Body `address,recipientName,phone,method`; AUTH. |
| POST `payments/vnpay/{orderId}` | KHỚP | Path/method đúng; AUTH. |
| GET `orders?page&size` + status/q/date | KHỚP có điều kiện | Backend nhận `status`, `q`, `createdAfter` (ISO date-time), `page,size,sort`; nếu FE gửi tên date khác thì LỆCH tên query. |
| GET `orders/{orderId}` | KHỚP | AUTH. |
| POST `products` | KHỚP | Body `ProductCreateRequest`; cần `product:create`. |
| PATCH/PUT/DELETE `products/{productId}` | KHỚP | Cả ba method tồn tại; quyền lần lượt update/update/delete. |
| POST `variants` | KHỚP | Cần `variant:create`. |
| PATCH/DELETE `variants/{variantId}` | KHỚP | Cần `variant:update`/`variant:delete`. |
| GET `attributes?page&size` | KHỚP | PUBLIC, `page,size`. |
| GET `attributes/{attributeId}/values?page&size` | KHỚP | Path đúng, `page,size`; PUBLIC. |
| POST/DELETE `product-attributes[/{productValueId}]` | KHỚP | POST cần `attribute:create`, DELETE cần `attribute:delete`. |
| POST/DELETE `variant-attributes[/{variantValueId}]` | KHỚP | POST cần `variant:create`, DELETE cần `variant:delete`. |
| POST `uploads/products/{productId}/thumbnail` | KHỚP | Multipart field bắt buộc là `file`; cần `media:create`. |
| POST `uploads/variants/{variantId}` | KHỚP | Multipart field bắt buộc là `file`; cần `media:create`. |

Không có dòng FE nào trong danh sách đã cho là `BE CHƯA CÓ`. Các điểm có thể gây lỗi dù path khớp là sai tên query filter/date, dùng `limit` thay `size`, thiếu credentials cho cookie, hoặc thiếu authority admin.

## 4. Riêng nhóm auth

### `GET /api/v1/auth/me`

Backend trả `ApiResponse<MeResponse>`, tức dữ liệu nằm trong `data` và có các field:

`userId`, `email`, `name`, `phone`, `address`, `roleCode`, `roleName`, `permissions`, `hasPassword` ([MeResponse.java](src/main/java/com/project/dacn/dto/response/auth/MeResponse.java):5-15).

Vì vậy bốn field FE yêu cầu đều có trong response thật và đúng tên:

| FE cần | Backend | Kết quả |
|---|---|---|
| `name` | `name` | KHỚP |
| `phone` | `phone` | KHỚP |
| `address` | `address` | KHỚP |
| `hasPassword` | `hasPassword` | KHỚP, kiểu boolean primitive |

### Refresh token

Trong backend hiện tại, `POST /api/v1/auth/refresh` **không nhận `refreshToken` trong request body**. Controller đọc `@CookieValue(name = "refresh_token")`, từ chối nếu cookie thiếu/rỗng, rồi chỉ nội bộ tạo `new RefreshRequest(refreshToken)` để gọi service ([AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):96-111). `POST /api/v1/auth/logout` cũng đọc cookie ([AuthController.java](src/main/java/com/project/dacn/controller/auth/AuthController.java):115-128).

Cookie được phát khi login/refresh, có `HttpOnly`, `path=/api/v1/auth`, `SameSite` và `Secure` theo config ([RefreshTokenCookie.java](src/main/java/com/project/dacn/security/RefreshTokenCookie.java):17-53). Do đó cách FE “cookie HttpOnly, không gửi body” là **KHỚP với backend source hiện tại**; câu mô tả “BE nhận refreshToken trong body” không đúng với mã hiện tại. Điều kiện bắt buộc là request cross-origin phải gửi credentials và origin phải nằm trong allow-list; nếu `apiFetch` không đặt credentials phù hợp thì cookie sẽ không tới BE dù path/method đúng.

## 5. Kết luận và mức độ làm vỡ luồng

### P0 — làm vỡ đăng nhập ngay

1. **Cookie credentials/origin**: refresh và logout phụ thuộc cookie HttpOnly + `allowCredentials=true` + origin được allow-list. Thiếu credentials hoặc cấu hình `CORS_ALLOWED_ORIGINS` không chứa origin FE sẽ làm refresh thất bại, kéo theo mất phiên.
2. **Không được gửi refresh token trong body**: backend hiện đọc cookie; client chỉ gửi body không giúp refresh thành công.
3. **Sai shape response**: auth response phải đọc `data`; riêng `/auth/me` phải có `name`, `phone`, `address`, `hasPassword`. Bốn field này hiện có và đúng tên ở BE.

### P1 — làm vỡ mua hàng

1. **Thiếu access token/authority cho cart, checkout, orders và payment**: các endpoint này yêu cầu AUTH; gọi như public sẽ nhận 401.
2. **Sai body checkout/cart**: phải dùng `variantId,quantity` cho cart item và `address,recipientName,phone,method` cho checkout.
3. **Sai paging**: dùng `page,size` (zero-based, max size 100), không dùng `page,limit`.
4. **Sai query filter orders**: ngày phải là `createdAfter` theo ISO date-time; các tên khác không được bind vào tham số backend.

### P2 — làm vỡ chức năng catalog/admin

1. Sai tên filter product (`keyword`, `category_id`, `min_price`, `max_price`, `status`, `sort`).
2. Thiếu authority tương ứng cho các thao tác admin (`product:*`, `variant:*`, `attribute:*`, `media:create`).
3. Upload phải là multipart với field `file`, không phải JSON.

**Tổng kết:** theo danh sách FE được cung cấp, không có endpoint nào bị thiếu ở backend và không có lệch path/method. Các rủi ro còn lại là contract chi tiết (cookie credentials, query/body field, paging, quyền) chứ không phải thiếu route.
