# Payment service độc lập (VNPay sandbox)

Service được tách từ thư mục `payment`, dùng để test riêng chức năng thanh toán.
Không cần chạy order-service, MySQL, API Gateway hoặc frontend.
Thư mục `payment` gốc được giữ nguyên.

## 1. Các phần được giữ lại và đơn giản hóa

- Spring Boot 4.0.0, Java 21, Maven Wrapper.
- Ba API: tạo URL, nhận kết quả Return, nhận thông báo IPN.
- DTO request/response, chữ ký HMAC-SHA512 và thông báo kết quả VNPay.
- Bỏ `OrderServiceClient`, `RestTemplateConfig`, retry cập nhật đơn hàng.
- Bỏ JPA, MySQL, REST client và đóng gói WAR; chạy trực tiếp bằng JAR.
- Mã hóa URL thống nhất với dữ liệu ký; thời gian dùng GMT+7.
- Return URL trỏ thẳng về backend, hiển thị kết quả JSON trên trình duyệt.

`orderId` chỉ là **mã tham chiếu test** do bạn tự đặt, ví dụ `TEST001`.
Service không kiểm tra đơn hàng tồn tại và không cập nhật trạng thái đơn hàng.
Mỗi lần thử thanh toán mới, hãy dùng một mã tham chiếu mới.

## 2. Chạy service

Yêu cầu: JDK 21; lần đầu Maven có thể cần Internet để tải dependency.

Mở PowerShell tại thư mục này:

```powershell
cd D:\SQA\payment-service
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\mvnw.cmd spring-boot:run
```

Đã sao chép hai thông số merchant sandbox từ `payment` hiện tại vào
`application-local.properties` để bạn tiếp tục test với cấu hình đang có.
File này nằm trong `.gitignore`. Service không đọc cấu hình từ thư mục `payment`
khi chạy, nên có thể tách thư mục `payment-service` sang chỗ khác.

Nếu cần thay tài khoản sandbox, sửa hai dòng trong `application-local.properties`.
Nếu chưa có file này, tạo từ mẫu:

```powershell
Copy-Item application-local.properties.example application-local.properties
```

Sau đó điền `vnpay.tmn-code` và `vnpay.hash-secret` do VNPay sandbox cấp.
Một cách khác là xóa file local và đặt biến môi trường `VNPAY_TMN_CODE`,
`VNPAY_HASH_SECRET`. Không sử dụng thông tin mẫu trong file `.example` để thanh toán thật.

Địa chỉ mặc định: **http://localhost:8085/api/vnpay**.
Không chạy cùng lúc `payment` gốc trên cổng 8085. Có thể đổi cổng trước khi chạy:

```powershell
$env:PORT = '8086'
.\mvnw.cmd spring-boot:run
```

Return URL mặc định tự dùng cổng mới. Có thể ghi đè bằng `VNPAY_RETURN_URL`.
Nếu thiếu cấu hình merchant, service vẫn khởi động nhưng API tạo URL trả HTTP 503.

## 3. API tạo URL

**POST** `http://localhost:8085/api/vnpay/create-payment`

Header: `Content-Type: application/json`

```json
{
  "orderId": "TEST001",
  "amount": 150000
}
```

- `orderId`: 1–100 chữ cái không dấu hoặc chữ số, không khoảng trắng.
- `amount`: số nguyên VND, từ 1 đến 9999999999; chưa nhân 100.
- Không cần token hay tạo đơn hàng trước.

Ví dụ response HTTP 200 (URL dưới đây được viết gọn để minh họa):

```json
{
  "paymentUrl": "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?...",
  "orderId": "TEST001",
  "amount": 150000,
  "message": "Tạo URL thanh toán thành công"
}
```

Test nhanh từ PowerShell:

```powershell
$body = @{ orderId = "TEST$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"; amount = 150000 } | ConvertTo-Json
$payment = Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:8085/api/vnpay/create-payment' `
    -ContentType 'application/json' -Body $body
$payment.paymentUrl
```

Copy **toàn bộ** `paymentUrl` vào trình duyệt và thanh toán bằng thông tin test
do VNPay sandbox cung cấp. Sau khi hoàn tất/hủy giao dịch, trình duyệt quay về
API `payment-return` và hiển thị JSON. Việc tạo URL chỉ tạo dữ liệu và chữ ký
tại service; chưa chứng minh tài khoản merchant được VNPay chấp nhận.

Request thiếu/sai mã tham chiếu hoặc số tiền trả HTTP 400.

## 4. API nhận kết quả Return

**GET** `/api/vnpay/payment-return?<các tham số VNPay trả về>`

VNPay chuyển trình duyệt về đây theo Return URL đã gửi trong yêu cầu tạo thanh toán.
API xác thực chữ ký và merchant, đọc kết quả và đổi số tiền về VND.
Chỉ báo `success: true` khi cả `vnp_ResponseCode` và `vnp_TransactionStatus` là `00`.

Ví dụ response thành công:

```json
{
  "success": true,
  "message": "Giao dịch thành công",
  "orderId": "TEST001",
  "transactionId": "123456",
  "amount": 150000,
  "responseCode": "00",
  "responseMessage": "Giao dịch thành công"
}
```

Gọi URL không có tham số hoặc tự sửa tham số mà không ký lại sẽ nhận
`success: false`. Đây là xử lý dự kiến, không phải lỗi gọi order-service.
Kết quả thanh toán thất bại/chữ ký sai được trả trong JSON với HTTP 200;
thiếu cấu hình merchant trả HTTP 503.

## 5. API nhận IPN

**GET** `/api/vnpay/payment-ipn?<các tham số VNPay gửi>`

Đây là callback VNPay gọi trực tiếp từ máy chủ của họ. Service kiểm tra chữ ký,
merchant và dữ liệu rồi trả xác nhận đã nhận thông báo:

```json
{
  "RspCode": "00",
  "Message": "Confirm Success"
}
```

- `00`: đã nhận thông báo hợp lệ, kể cả thông báo giao dịch thất bại.
  Đây **không phải** kết luận khách thanh toán thành công.
- `97`: chữ ký thiếu hoặc không hợp lệ.
- `99`: dữ liệu callback không hợp lệ hoặc thiếu cấu hình merchant.
- IPN luôn trả HTTP 200; đọc `RspCode` để biết kết quả xác nhận.

Để VNPay gọi IPN thật, cần đăng ký URL HTTPS công khai trỏ đến endpoint này
với VNPay sandbox. `localhost` không truy cập được từ máy chủ VNPay.
Chỉ test tạo URL và Return trên cùng máy thì không cần IPN công khai.
Service không có thuộc tính `ipn-url` vì URL IPN được cấu hình phía VNPay,
không đưa vào URL thanh toán.

## 6. Đóng gói và chạy JAR

```powershell
.\mvnw.cmd package
& "$env:JAVA_HOME\bin\java.exe" -jar target\payment-service-0.0.1-SNAPSHOT.jar
```

## 7. Cấu trúc code và phạm vi bản test

```text
src/main/java/com/example/payment/
  PaymentApplication.java       Chạy ứng dụng
  controller/VNPayController    Ba API HTTP
  service/VNPayService          Tạo URL, xác thực và đọc kết quả
  config/VNPayConfig            Mã hóa URL và ký HMAC-SHA512
  dto/                         Request và response giữ từ payment gốc
```

Luồng chính: **Postman → tạo URL → trình duyệt/VNPay sandbox → Return JSON**.
IPN là một lời gọi riêng từ máy chủ VNPay về service.

Đây là bản test không lưu dữ liệu: không có lịch sử, tra cứu trạng thái, hoàn tiền,
đối chiếu số tiền callback với yêu cầu khởi tạo hoặc kiểm tra mã tham chiếu đã tạo.
Callback hợp lệ được đọc theo dữ liệu có chữ ký; không đánh dấu đơn hàng `PAID`.
IPN lặp lại chỉ nhận lại xác nhận, không có thao tác ghi hoặc retry cập nhật đơn.
Không dùng bản này làm hệ thống ghi nhận thanh toán thực tế.

Tham khảo giao thức: [Tài liệu thanh toán VNPay](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).
