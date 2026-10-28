# Test thanh toán VNPay qua Cloudflare Tunnel

Cài cloudflared (nếu chưa có):

```powershell
winget install --id Cloudflare.cloudflared
```

---

## Mỗi lần test

### 1. Mở tunnel

```powershell
cloudflared tunnel --url http://127.0.0.1:8080
```

Copy URL `https://...trycloudflare.com`. Giữ terminal này mở.

### 2. Cập nhật URL nếu đổi

Sửa `VNPAY_RETURN_URL`, `VNPAY_IPN_URL` trong `.env` và IPN URL trên dashboard.

Link dasboard vnpay: https://sandbox.vnpayment.vn/vnpaygw-sit-testing/user/login
Tên đang nhập: 0w0tahp@gmail.com
Password: Changeme123!

Vào mục **Cấu hình IPN URL** → **Cập nhật** → dán IPN URL mới → **Cập nhật**.



File .env:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require
SPRING_DATASOURCE_USERNAME=postgres.wdnewfdxkzbnarkvpqnm
SPRING_DATASOURCE_PASSWORD=matkhausupabase
SPRING_PROFILES_ACTIVE=dev


CLOUDINARY_CLOUD_NAME=ibk3z2nt
CLOUDINARY_API_KEY=757671162169917
CLOUDINARY_API_SECRET=1nJeOdnVOYmWMdwm_Z6XihiypAg
CLOUDINARY_SECURE=true

REDIS_HOST=imaginative-clary-adroit-16441.db.redis.io
REDIS_PORT=13357
REDIS_USERNAME=default
REDIS_PASSWORD=KJpXNJX24lN6bbCVoX1CYZZby0psAVrr
REDIS_SSL_ENABLED=false


VNPAY_TMN_CODE=K7ECSHEK
VNPAY_HASH_SECRET=WLPXYYBYDPABFWVFWIAJMTBFSCIUAJAH
JWT_SECRET=S7tNnAVuu+04t8yrbUeQOAglqXAusgcpOg2ze0yCg8uIIBDWsjqwew6X3jaO6+2H
VNPAY_RETURN_URL=
VNPAY_IPN_URL=
```

### 3. Start backend

```powershell
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### 4. Kiểm tra

```powershell
curl.exe https://<tunnel-url>/actuator/health
```

Kỳ vọng: `{"status":"UP"}`.

### 5. Mở Swagger

```text
https://<tunnel-url>/swagger-ui.html
```

Luôn dùng `https`, không dùng `http`.

---

## Test thanh toán

### 1. Register

`POST /api/v1/auth/register`

```json
{"email":"test-payment@example.com","password":"Changeme123","name":"Test","phone":"0900000000"}
```

### 2. Login

`POST /api/v1/auth/login`

```json
{"email":"test-payment@example.com","password":"Changeme123"}
```

Copy `data.accessToken`.

### 3. Authorize

Bấm **Authorize**, dán token (không kèm `Bearer`), Authorize.

### 4. Lấy variant

`GET /api/v1/variants?size=1`
variantId = ember-tetra-default

Lấy số id trả về -> 8.

### 5. Thêm giỏ hàng

`POST /api/v1/cart/items`

```json
{"variantId": 1, "quantity": 1}
```

### 6. Checkout

`POST /api/v1/cart/checkout`

```json
{"address":"123 Test, HCMC","recipientName":"Test","phone":"0900000000","method":"CREDIT_CARD"}
```

Copy `data.orderId`. Không dùng `COD`.

### 7. Tạo payUrl

`POST /api/v1/payments/vnpay/{orderId}`

Copy `data.payUrl`, mở trên trình duyệt.

### 8. Thanh toán

Chọn **Thẻ ATM nội địa** → `NCB`:

| Trường | Giá trị |
|---|---|
| Số thẻ | `9704198526191432198` |
| Tên | `NGUYEN VAN A` |
| Ngày phát hành | `07/15` |
| OTP | `123456` |

Không dùng QR, không random số thẻ.

### 9. Kiểm tra kết quả

Return URL hiện JSON:

```json
{"valid":"true","responseCode":"00"}
```

Xác nhận đơn:

```text
GET /api/v1/payments/orders/{orderId}   → status CONFIRMED
GET /api/v1/orders/{orderId}            → status CONFIRMED
```

Log backend có:

```text
VNPay payment ... confirmed for order ...
```

> Return chỉ xác minh chữ ký. Đơn chỉ chốt khi IPN tới. Nếu vẫn `PENDING`, kiểm tra lại IPN URL trên dashboard và tunnel.

---

