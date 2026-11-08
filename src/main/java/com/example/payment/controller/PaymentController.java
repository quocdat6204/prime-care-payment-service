package com.example.payment.controller;

import com.example.payment.dto.request.PaymentRequest;
import com.example.payment.dto.response.ApiResponse;
import com.example.payment.dto.response.InvoiceResponse;
import com.example.payment.dto.response.PaymentResponse;
import com.example.payment.dto.response.PaymentReturnResponse;
import com.example.payment.model.Invoice;
import com.example.payment.model.PaymentTransaction;
import com.example.payment.service.PaymentService;
import com.example.payment.util.InvoiceStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller tiếp nhận các yêu cầu HTTP thanh toán tại đường dẫn {@code /api/vnpay}.
 */
@RestController
@RequestMapping({"/api/vnpay", "/api/payments"})
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PaymentController {

    PaymentService paymentService;

    /**
     * Tạo URL thanh toán VNPay.
     * Hỗ trợ thanh toán lịch hẹn khám (EXAMINATION / SERVICES) hoặc thanh toán độc lập.
     */
    @PostMapping("/create-payment")
    public ApiResponse<PaymentResponse> createPayment(
            @RequestBody @Valid PaymentRequest request, HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            ip = "127.0.0.1";
        }
        PaymentResponse response = paymentService.createPayment(request, ip);
        return ApiResponse.<PaymentResponse>builder()
                .result(response)
                .build();
    }

    /**
     * Thu tiền mặt tại quầy cho các dịch vụ chỉ định (CHỈ ADMIN được phép thực hiện).
     */
    @PostMapping("/cash-payment")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PaymentReturnResponse> payWithCash(
            @RequestBody @Valid PaymentRequest request) {
        PaymentReturnResponse response = paymentService.payWithCash(request);
        return ApiResponse.<PaymentReturnResponse>builder()
                .result(response)
                .build();
    }

    /**
     * Xử lý callback khi trình duyệt chuyển hướng về từ VNPay.
     * Nếu trình duyệt trực tiếp điều hướng tới đây từ VNPay (Accept text/html / không phải application/json),
     * tự động redirect 302 sang trang Lịch hẹn FE và xóa sạch các query params nhạy cảm khỏi URL.
     * Nếu FE (PaymentReturn.jsx) gọi AJAX, trả về JSON ApiResponse.
     */
    @GetMapping("/payment-return")
    public Object paymentReturn(
            @RequestParam Map<String, String> params,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            jakarta.servlet.http.HttpServletResponse httpResponse) throws java.io.IOException {
        PaymentReturnResponse response = paymentService.processPaymentCallback(params);

        if (acceptHeader == null || !acceptHeader.contains("application/json")) {
            String redirectUrl = "http://localhost:5173/patient/appointments?paymentSuccess=" + response.isSuccess();
            httpResponse.sendRedirect(redirectUrl);
            return null;
        }

        return ApiResponse.<PaymentReturnResponse>builder()
                .result(response)
                .build();
    }

    /**
     * Xử lý IPN webhook nhận thông báo trực tiếp từ máy chủ VNPay (chuẩn response VNPay).
     */
    @GetMapping("/payment-ipn")
    public Map<String, String> paymentIpn(@RequestParam Map<String, String> params) {
        PaymentReturnResponse response = paymentService.processPaymentCallback(params);
        if (!response.isSuccess() && "Chữ ký VNPay không hợp lệ".equals(response.getMessage())) {
            return Map.of("RspCode", "97", "Message", "Invalid signature");
        }
        return Map.of("RspCode", "00", "Message", "Confirm Success");
    }

    /**
     * Lấy danh sách hóa đơn theo ID lịch hẹn.
     * Admin được phép xem tất cả. Người dùng chỉ xem được nếu đúng là lịch hẹn của họ.
     */
    @GetMapping("/appointments/{appointmentId}/invoices")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'PATIENT')")
    public ApiResponse<List<InvoiceResponse>> getInvoicesByAppointment(@PathVariable Long appointmentId) {
        return ApiResponse.<List<InvoiceResponse>>builder()
                .result(paymentService.getInvoicesByAppointment(appointmentId))
                .build();
    }

    /**
     * Lấy danh sách lịch sử giao dịch của một hóa đơn (CHỈ ADMIN được phép xem).
     */
    @GetMapping("/invoices/{invoiceId}/transactions")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<PaymentTransaction>> getTransactionsByInvoice(@PathVariable Long invoiceId) {
        return ApiResponse.<List<PaymentTransaction>>builder()
                .result(paymentService.getTransactionsByInvoice(invoiceId))
                .build();
    }

    /**
     * Lấy danh sách tất cả hóa đơn (CHỈ ADMIN được phép xem).
     * Hỗ trợ lọc theo trạng thái (status) và phân trang.
     */
    @GetMapping("/invoices")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Page<Invoice>> getAllInvoices(
            @RequestParam(required = false) InvoiceStatus status,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.<Page<Invoice>>builder()
                .result(paymentService.getAllInvoices(status, pageable))
                .build();
    }
}
