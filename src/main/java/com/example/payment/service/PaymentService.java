package com.example.payment.service;

import com.example.payment.client.ClinicClient;
import com.example.payment.configuration.VNPayConfig;
import com.example.payment.dto.event.PaymentSuccessEvent;
import com.example.payment.dto.request.BindServiceInvoiceRequest;
import com.example.payment.dto.request.PaymentRequest;
import com.example.payment.dto.response.ApiResponse;
import com.example.payment.dto.response.ChargeResponse;
import com.example.payment.dto.response.InvoiceResponse;
import com.example.payment.dto.response.PaymentResponse;
import com.example.payment.dto.response.PaymentReturnResponse;
import com.example.payment.dto.response.ServiceItemResponse;
import com.example.payment.exception.AppException;
import com.example.payment.exception.ErrorCode;
import com.example.payment.model.*;
import com.example.payment.producer.PaymentKafkaProducer;
import com.example.payment.repository.InvoiceRepository;
import com.example.payment.repository.PaymentTransactionRepository;
import com.example.payment.security.Access;
import com.example.payment.util.*;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
public class PaymentService {

    final InvoiceRepository invoiceRepository;
    final PaymentTransactionRepository paymentTransactionRepository;
    final ClinicClient clinicClient;
    final PaymentKafkaProducer paymentKafkaProducer;

    @Value("${vnpay.tmn-code}")
    String tmnCode;

    @Value("${vnpay.hash-secret}")
    String hashSecret;

    @Value("${vnpay.pay-url}")
    String payUrl;

    @Value("${vnpay.return-url}")
    String returnUrl;

    /**
     * Khởi tạo giao dịch thanh toán: kiểm tra thông tin từ clinic-service,
     * lưu Invoice và Transaction, tạo VNPay URL.
     */
    @Transactional
    public PaymentResponse createPayment(PaymentRequest request, String ipAddress) {
        requireConfiguration();

        // Luồng 1: Thanh toán theo Appointment của Clinic Service
        if (request.getAppointmentId() != null) {
            InvoiceType type = request.getType() != null ? request.getType() : InvoiceType.EXAMINATION;
            Invoice invoice;
            ChargeResponse charge;

            if (type == InvoiceType.EXAMINATION) {
                // Kiểm tra xem đã có hóa đơn phí khám được thanh toán chưa
                Optional<Invoice> paidInvoice = invoiceRepository.findByAppointmentIdAndTypeAndStatus(
                        request.getAppointmentId(), InvoiceType.EXAMINATION, InvoiceStatus.PAID);
                if (paidInvoice.isPresent()) {
                    throw new AppException(ErrorCode.DATA_CONFLICT, "Lịch hẹn #" + request.getAppointmentId() + " đã thanh toán phí khám trước đó");
                }

                // Gọi Clinic Service qua Feign để lấy phí khám đã chốt
                ApiResponse<ChargeResponse> chargeResponse = clinicClient.getExaminationCharge(request.getAppointmentId());
                if (chargeResponse == null || chargeResponse.getResult() == null) {
                    throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không thể lấy thông tin phí khám từ clinic-service cho lịch hẹn #" + request.getAppointmentId());
                }
                charge = chargeResponse.getResult();

                // Kiểm tra quyền sở hữu lịch hẹn
                if (!Access.hasRole("ADMIN") && !charge.getPatientId().equals(Access.userId())) {
                    throw new AppException(ErrorCode.UNAUTHORIZED, "Bạn không có quyền thanh toán cho lịch hẹn của người khác");
                }

                // Tìm hóa đơn PENDING cũ nếu có, hoặc tạo mới
                invoice = invoiceRepository.findByAppointmentIdAndTypeAndStatus(
                        request.getAppointmentId(), InvoiceType.EXAMINATION, InvoiceStatus.PENDING)
                        .orElse(null);

                if (invoice == null) {
                    invoice = Invoice.builder()
                            .appointmentId(charge.getAppointmentId())
                            .patientId(charge.getPatientId())
                            .type(InvoiceType.EXAMINATION)
                            .amount(charge.getAmount())
                            .status(InvoiceStatus.PENDING)
                            .createdAt(Instant.now())
                            .build();
                } else {
                    invoice.setAmount(charge.getAmount());
                }
                invoice = invoiceRepository.save(invoice);

            } else {
                // Type == SERVICES (thanh toán dịch vụ chỉ định)
                if (request.getServiceItemIds() == null || request.getServiceItemIds().isEmpty()) {
                    throw new AppException(ErrorCode.INVALID_REQUEST, "Danh sách dịch vụ chỉ định (serviceItemIds) không được để trống");
                }

                invoice = null;
                charge = null;

                // Kiểm tra xem đã có hóa đơn SERVICES nào đang PENDING của ca khám này không
                List<Invoice> pendingInvoices = invoiceRepository.findByAppointmentIdAndTypeAndStatusOrderByCreatedAtDesc(
                        request.getAppointmentId(), InvoiceType.SERVICES, InvoiceStatus.PENDING);

                // 1. Thử tái sử dụng hóa đơn PENDING cũ nếu cùng đúng nhóm dịch vụ (thanh toán lại -> 0 rác DB)
                for (Invoice pending : pendingInvoices) {
                    try {
                        BindServiceInvoiceRequest retryBind = BindServiceInvoiceRequest.builder()
                                .invoiceId(pending.getId())
                                .itemIds(request.getServiceItemIds())
                                .build();
                        ApiResponse<ChargeResponse> bindResponse = clinicClient.bindServiceInvoice(request.getAppointmentId(), retryBind);
                        if (bindResponse != null && bindResponse.getResult() != null) {
                            invoice = pending;
                            charge = bindResponse.getResult();
                            break;
                        }
                    } catch (Exception ignored) {
                        // Không khớp đúng nhóm dịch vụ cũ, sẽ xử lý ở bước tiếp theo
                    }
                }

                // 2. Nếu không khớp hóa đơn PENDING nào (ví dụ có dịch vụ mới thêm vào hoặc đổi dịch vụ)
                if (invoice == null) {
                    // Hủy các hóa đơn PENDING cũ không còn khớp mặt hàng/số tiền
                    for (Invoice oldPending : pendingInvoices) {
                        oldPending.setStatus(InvoiceStatus.CANCELLED);
                        invoiceRepository.save(oldPending);
                    }

                    // Tạo hóa đơn mới cho nhóm dịch vụ hiện tại
                    invoice = Invoice.builder()
                            .appointmentId(request.getAppointmentId())
                            .patientId(0L) // tạm thời, sẽ cập nhật lại sau khi bind
                            .type(InvoiceType.SERVICES)
                            .amount(BigDecimal.ZERO)
                            .status(InvoiceStatus.PENDING)
                            .createdAt(Instant.now())
                            .build();
                    invoice = invoiceRepository.save(invoice);

                    BindServiceInvoiceRequest bindRequest = BindServiceInvoiceRequest.builder()
                            .invoiceId(invoice.getId())
                            .itemIds(request.getServiceItemIds())
                            .build();
                    ApiResponse<ChargeResponse> bindResponse = clinicClient.bindServiceInvoice(request.getAppointmentId(), bindRequest);
                    if (bindResponse == null || bindResponse.getResult() == null) {
                        throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không thể gắn hóa đơn cho dịch vụ chỉ định tại clinic-service");
                    }
                    charge = bindResponse.getResult();
                }

                if (charge.getAmount() == null || charge.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new AppException(ErrorCode.INVALID_REQUEST, "Không có dịch vụ chỉ định nào cần thanh toán");
                }

                // Kiểm tra quyền sở hữu lịch hẹn
                if (!Access.hasRole("ADMIN") && !charge.getPatientId().equals(Access.userId())) {
                    throw new AppException(ErrorCode.UNAUTHORIZED, "Bạn không có quyền thanh toán cho lịch hẹn của người khác");
                }

                invoice.setAmount(charge.getAmount());
                invoice.setPatientId(charge.getPatientId());
                invoice = invoiceRepository.save(invoice);
            }

            // Tạo mã giao dịch duy nhất cho lần thử thanh toán này (1 Invoice có nhiều Transactions)
            String txnRef = "TXN" + invoice.getId() + "T" + System.currentTimeMillis();

            PaymentTransaction transaction = PaymentTransaction.builder()
                    .invoiceId(invoice.getId())
                    .txnRef(txnRef)
                    .amount(invoice.getAmount())
                    .paymentMethod("VNPAY")
                    .status(TransactionStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();
            paymentTransactionRepository.save(transaction);

            // Tạo URL thanh toán VNPay với thời gian hết hạn đồng bộ theo expiresAt
            long amountVnd = invoice.getAmount().longValue();
            String vnpayUrl = buildVnPayUrl(txnRef, amountVnd, "Thanh toan hoa don #" + invoice.getId(), ipAddress, charge.getExpiresAt());

            return PaymentResponse.builder()
                    .paymentUrl(vnpayUrl)
                    .orderId(txnRef)
                    .txnRef(txnRef)
                    .invoiceId(invoice.getId())
                    .amount(amountVnd)
                    .message("Tạo URL thanh toán thành công")
                    .build();
        }

        // Luồng 2: Standalone / Test mode khi không truyền appointmentId
        if (request.getOrderId() == null || !request.getOrderId().matches("[A-Za-z0-9]{1,100}")) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "orderId phải gồm 1-100 chữ cái hoặc chữ số, không có khoảng trắng");
        }
        if (request.getAmount() == null || request.getAmount() <= 0 || request.getAmount() > 9_999_999_999L) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "amount phải từ 1 đến 9999999999 VND");
        }

        String vnpayUrl = buildVnPayUrl(request.getOrderId(), request.getAmount(), "Thanh toan don hang " + request.getOrderId(), ipAddress, null);
        return PaymentResponse.builder()
                .paymentUrl(vnpayUrl)
                .orderId(request.getOrderId())
                .txnRef(request.getOrderId())
                .amount(request.getAmount())
                .message("Tạo URL thanh toán thành công")
                .build();
    }

    /**
     * Tạo URL VNPay kèm chữ ký số và thời gian hết hạn (vnp_ExpireDate).
     */
    private String buildVnPayUrl(String txnRef, long amountVnd, String orderInfo, String ipAddress, Instant expiresAt) {
        var now = ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        var format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_Amount", String.valueOf(amountVnd * 100));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", orderInfo);
        params.put("vnp_OrderType", "other");
        params.put("vnp_ReturnUrl", returnUrl);
        params.put("vnp_IpAddr", ipAddress);
        params.put("vnp_CreateDate", now.format(format));

        // Đồng bộ vnp_ExpireDate với Clinic (hết hạn trước Clinic 30s để đảm bảo tiền về kịp trước khi hủy slot)
        Instant vnpayDeadline;
        if (expiresAt != null) {
            Instant safeExpiresAt = expiresAt.minusSeconds(30);
            vnpayDeadline = safeExpiresAt.isAfter(now.toInstant()) ? safeExpiresAt : expiresAt;
        } else {
            vnpayDeadline = now.plusMinutes(10).toInstant();
        }
        params.put("vnp_ExpireDate", vnpayDeadline.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(format));

        params.put("vnp_Locale", "vn");

        return payUrl + "?" + VNPayConfig.buildQuery(params)
                + "&vnp_SecureHash=" + VNPayConfig.hashAllFields(params, hashSecret);
    }

    /**
     * Xử lý kết quả callback từ VNPay (cho cả Return URL và IPN webhook).
     */
    @Transactional
    public PaymentReturnResponse processPaymentCallback(Map<String, String> params) {
        requireConfiguration();

        if (!verifyPayment(params)) {
            return PaymentReturnResponse.builder()
                    .success(false)
                    .message("Chữ ký VNPay không hợp lệ")
                    .build();
        }

        PaymentReturnResponse result = readPaymentResult(params);
        String txnRef = result.getOrderId();

        // Tìm Transaction trong database
        Optional<PaymentTransaction> txOpt = paymentTransactionRepository.findByTxnRef(txnRef);
        if (txOpt.isPresent()) {
            PaymentTransaction transaction = txOpt.get();
            transaction.setVnpTransactionNo(result.getTransactionId());
            transaction.setBankCode(params.get("vnp_BankCode"));
            transaction.setPayDate(params.get("vnp_PayDate"));
            transaction.setResponseCode(result.getResponseCode());

            result.setInvoiceId(transaction.getInvoiceId());

            if (result.isSuccess()) {
                transaction.setStatus(TransactionStatus.SUCCESS);

                // Cập nhật trạng thái Invoice nếu đang PENDING và gửi Kafka event sang Clinic Service
                Invoice invoice = invoiceRepository.findById(transaction.getInvoiceId()).orElse(null);
                if (invoice != null && (invoice.getStatus() == InvoiceStatus.PENDING || invoice.getStatus() == InvoiceStatus.PAID)) {
                    if (invoice.getStatus() == InvoiceStatus.PENDING) {
                        invoice.setStatus(InvoiceStatus.PAID);
                        invoice.setPaidAt(Instant.now());
                        invoiceRepository.save(invoice);
                    }

                    // Bắn message sang Kafka để Clinic Service cập nhật
                    PaymentSuccessEvent event = PaymentSuccessEvent.builder()
                            .appointmentId(invoice.getAppointmentId())
                            .invoiceId(invoice.getId())
                            .patientId(invoice.getPatientId())
                            .type(invoice.getType().name())
                            .amount(invoice.getAmount())
                            .txnRef(transaction.getTxnRef())
                            .vnpTransactionNo(transaction.getVnpTransactionNo())
                            .paidAt(invoice.getPaidAt() != null ? invoice.getPaidAt() : Instant.now())
                            .build();

                    paymentKafkaProducer.sendPaymentSuccessEvent(event);
                    log.info("Payment success recorded and Kafka event sent for invoiceId={}", invoice.getId());
                }
            } else {
                transaction.setStatus(TransactionStatus.FAILED);
            }

            paymentTransactionRepository.save(transaction);
        }

        return result;
    }

    public boolean verifyPayment(Map<String, String> params) {
        requireConfiguration();
        String signature = params.get("vnp_SecureHash");
        if (signature == null || !signature.matches("[0-9a-fA-F]{128}")) {
            return false;
        }
        Map<String, String> fields = new HashMap<>(params);
        fields.remove("vnp_SecureHash");
        fields.remove("vnp_SecureHashType");
        String expected = VNPayConfig.hashAllFields(fields, hashSecret);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public PaymentReturnResponse readPaymentResult(Map<String, String> params) {
        String reference = params.get("vnp_TxnRef");
        if (reference == null || !reference.matches("[A-Za-z0-9_]{1,100}")) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Thiếu hoặc sai vnp_TxnRef");
        }
        if (!tmnCode.equals(params.get("vnp_TmnCode"))) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "vnp_TmnCode không khớp cấu hình");
        }
        String amountText = params.get("vnp_Amount");
        if (amountText == null || !amountText.matches("[0-9]{1,12}")) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Thiếu hoặc sai vnp_Amount");
        }
        long rawAmount = Long.parseLong(amountText);
        if (rawAmount <= 0 || rawAmount % 100 != 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "vnp_Amount không hợp lệ");
        }
        String responseCode = params.get("vnp_ResponseCode");
        String transactionStatus = params.get("vnp_TransactionStatus");
        if (responseCode == null || !responseCode.matches("[0-9]{2}")
                || transactionStatus == null || !transactionStatus.matches("[0-9]{2}")) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Thiếu hoặc sai mã kết quả VNPay");
        }
        boolean success = "00".equals(responseCode) && "00".equals(transactionStatus);
        String message = getResponseMessage(responseCode);
        if (!success && "00".equals(responseCode)) {
            message = "Giao dịch chưa thành công, trạng thái: " + transactionStatus;
        }
        return PaymentReturnResponse.builder()
                .success(success)
                .message(message)
                .orderId(reference)
                .transactionId(params.get("vnp_TransactionNo"))
                .amount(rawAmount / 100)
                .responseCode(responseCode)
                .responseMessage(message)
                .build();
    }

    /**
     * Lấy danh sách hóa đơn theo lịch hẹn với kiểm tra phân quyền.
     * Người dùng thông thường sẽ không nhìn thấy các hóa đơn đã CANCELLED.
     * Với hóa đơn SERVICES, sẽ đính kèm danh sách chi tiết các dịch vụ.
     */
    @Transactional(readOnly = true)
    public List<InvoiceResponse> getInvoicesByAppointment(Long appointmentId) {
        List<Invoice> invoices = invoiceRepository.findByAppointmentId(appointmentId);

        boolean isAdmin = Access.hasRole("ADMIN");
        if (!isAdmin) {
            Long currentUserId = Access.userId();

            // Nếu lịch hẹn đã có hóa đơn: kiểm tra xem có hóa đơn nào của bệnh nhân khác không
            boolean hasOtherPatientInvoice = invoices.stream()
                    .anyMatch(inv -> inv.getPatientId() != null && !inv.getPatientId().equals(currentUserId));
            if (hasOtherPatientInvoice) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Bạn không có quyền xem hóa đơn của lịch hẹn này");
            }

            // Nếu chưa có hóa đơn nào trong DB: kiểm tra xem lịch hẹn này có phải của current user không
            if (invoices.isEmpty()) {
                try {
                    ApiResponse<ChargeResponse> charge = clinicClient.getExaminationCharge(appointmentId);
                    if (charge != null && charge.getResult() != null) {
                        if (!currentUserId.equals(charge.getResult().getPatientId())) {
                            throw new AppException(ErrorCode.UNAUTHORIZED, "Bạn không có quyền xem hóa đơn của lịch hẹn này");
                        }
                    }
                } catch (AppException e) {
                    throw e;
                } catch (Exception ignored) {
                    // Nếu appointment không tồn tại hoặc đã qua hạn, trả về list rỗng
                }
            }

            // Người dùng thông thường: Lọc bỏ các hóa đơn đã bị CANCELLED
            invoices = invoices.stream()
                    .filter(inv -> inv.getStatus() != InvoiceStatus.CANCELLED)
                    .toList();
        }

        if (invoices.isEmpty()) {
            return List.of();
        }

        // Lấy danh sách dịch vụ của ca khám từ clinic-service để map vào các invoice SERVICES
        boolean hasServiceInvoice = invoices.stream().anyMatch(inv -> inv.getType() == InvoiceType.SERVICES);
        Map<Long, List<ServiceItemResponse>> servicesByInvoiceId = new HashMap<>();

        if (hasServiceInvoice) {
            try {
                ApiResponse<List<ServiceItemResponse>> response = clinicClient.getAppointmentServices(appointmentId);
                if (response != null && response.getResult() != null) {
                    servicesByInvoiceId = response.getResult().stream()
                            .filter(s -> s.getInvoiceId() != null)
                            .collect(Collectors.groupingBy(ServiceItemResponse::getInvoiceId));
                }
            } catch (Exception e) {
                log.warn("Không thể lấy danh sách dịch vụ từ clinic-service cho lịch hẹn #{}: {}", appointmentId, e.getMessage());
            }
        }

        final Map<Long, List<ServiceItemResponse>> finalServicesMap = servicesByInvoiceId;
        return invoices.stream().map(inv -> {
            List<ServiceItemResponse> items = null;
            if (inv.getType() == InvoiceType.SERVICES) {
                items = finalServicesMap.getOrDefault(inv.getId(), List.of());
            }
            return InvoiceResponse.builder()
                    .id(inv.getId())
                    .appointmentId(inv.getAppointmentId())
                    .patientId(inv.getPatientId())
                    .type(inv.getType())
                    .amount(inv.getAmount())
                    .status(inv.getStatus())
                    .createdAt(inv.getCreatedAt())
                    .paidAt(inv.getPaidAt())
                    .services(items)
                    .build();
        }).toList();
    }

    /**
     * Lấy danh sách giao dịch của một hóa đơn (chỉ ADMIN).
     */
    @Transactional(readOnly = true)
    public List<PaymentTransaction> getTransactionsByInvoice(Long invoiceId) {
        return paymentTransactionRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId);
    }

    /**
     * Lấy danh sách tất cả hóa đơn (CHỈ ADMIN), hỗ trợ lọc theo trạng thái và phân trang.
     */
    @Transactional(readOnly = true)
    public Page<Invoice> getAllInvoices(InvoiceStatus status, Pageable pageable) {
        if (status != null) {
            return invoiceRepository.findByStatus(status, pageable);
        }
        return invoiceRepository.findAll(pageable);
    }

    private void requireConfiguration() {
        if (tmnCode.isBlank() || hashSecret.isBlank()) {
            throw new AppException(ErrorCode.VNPAY_CONFIG_ERROR, "Chưa cấu hình VNPAY_TMN_CODE và VNPAY_HASH_SECRET");
        }
    }

    /**
     * Thu tiền mặt tại quầy cho các dịch vụ chỉ định (Dành cho ADMIN).
     * Tận dụng luồng thanh toán hiện tại:
     * - Tái sử dụng hoặc tạo mới Invoice với trạng thái PAID
     * - Lưu PaymentTransaction với phương thức CASH và trạng thái SUCCESS
     * - Gửi PaymentSuccessEvent qua Kafka & gọi trực tiếp confirmServices để clinic-service cập nhật
     */
    @Transactional
    public PaymentReturnResponse payWithCash(PaymentRequest request) {
        Access.require(Access.hasRole("ADMIN"));

        if (request.getAppointmentId() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Mã lịch hẹn (appointmentId) không được để trống");
        }

        if (request.getServiceItemIds() == null || request.getServiceItemIds().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Danh sách dịch vụ chỉ định (serviceItemIds) không được để trống");
        }

        Invoice invoice = null;
        ChargeResponse charge = null;

        // Tìm các hóa đơn PENDING cũ của ca khám này
        List<Invoice> pendingInvoices = invoiceRepository.findByAppointmentIdAndTypeAndStatusOrderByCreatedAtDesc(
                request.getAppointmentId(), InvoiceType.SERVICES, InvoiceStatus.PENDING);

        // 1. Thử tái sử dụng hóa đơn PENDING cũ nếu cùng đúng nhóm dịch vụ
        for (Invoice pending : pendingInvoices) {
            try {
                BindServiceInvoiceRequest retryBind = BindServiceInvoiceRequest.builder()
                        .invoiceId(pending.getId())
                        .itemIds(request.getServiceItemIds())
                        .build();
                ApiResponse<ChargeResponse> bindResponse = clinicClient.bindServiceInvoice(request.getAppointmentId(), retryBind);
                if (bindResponse != null && bindResponse.getResult() != null) {
                    invoice = pending;
                    charge = bindResponse.getResult();
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Nếu không khớp hóa đơn PENDING nào, hủy các PENDING cũ và tạo hóa đơn mới
        if (invoice == null) {
            for (Invoice oldPending : pendingInvoices) {
                oldPending.setStatus(InvoiceStatus.CANCELLED);
                invoiceRepository.save(oldPending);
            }

            invoice = Invoice.builder()
                    .appointmentId(request.getAppointmentId())
                    .patientId(0L)
                    .type(InvoiceType.SERVICES)
                    .amount(BigDecimal.ZERO)
                    .status(InvoiceStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();
            invoice = invoiceRepository.save(invoice);

            BindServiceInvoiceRequest bindRequest = BindServiceInvoiceRequest.builder()
                    .invoiceId(invoice.getId())
                    .itemIds(request.getServiceItemIds())
                    .build();
            ApiResponse<ChargeResponse> bindResponse = clinicClient.bindServiceInvoice(request.getAppointmentId(), bindRequest);
            if (bindResponse == null || bindResponse.getResult() == null) {
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không thể gắn hóa đơn cho dịch vụ chỉ định tại clinic-service");
            }
            charge = bindResponse.getResult();
        }

        if (charge.getAmount() == null || charge.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Không có dịch vụ chỉ định nào cần thanh toán");
        }

        Instant now = Instant.now();
        invoice.setAmount(charge.getAmount());
        invoice.setPatientId(charge.getPatientId());
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(now);
        invoice = invoiceRepository.save(invoice);

        // Tạo giao dịch tiền mặt (CASH) trạng thái SUCCESS
        String txnRef = "CASH-" + invoice.getId() + "-" + System.currentTimeMillis();
        PaymentTransaction transaction = PaymentTransaction.builder()
                .invoiceId(invoice.getId())
                .txnRef(txnRef)
                .amount(invoice.getAmount())
                .paymentMethod("CASH")
                .status(TransactionStatus.SUCCESS)
                .vnpTransactionNo("CASH_COUNTER")
                .payDate(DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(now))
                .createdAt(now)
                .build();
        paymentTransactionRepository.save(transaction);

        // Bắn Kafka Outbox Event để đồng bộ sang các microservices (chuẩn Event-Driven)
        PaymentSuccessEvent event = PaymentSuccessEvent.builder()
                .appointmentId(invoice.getAppointmentId())
                .invoiceId(invoice.getId())
                .patientId(invoice.getPatientId())
                .type(invoice.getType().name())
                .amount(invoice.getAmount())
                .txnRef(transaction.getTxnRef())
                .vnpTransactionNo("CASH_COUNTER")
                .paidAt(now)
                .build();
        paymentKafkaProducer.sendPaymentSuccessEvent(event);

        log.info("Cash payment completed successfully and Kafka event sent for appointmentId={}, invoiceId={}, amount={}",
                invoice.getAppointmentId(), invoice.getId(), invoice.getAmount());

        return PaymentReturnResponse.builder()
                .success(true)
                .orderId(txnRef)
                .amount(invoice.getAmount().longValue())
                .invoiceId(invoice.getId())
                .transactionId("CASH_COUNTER")
                .responseCode("00")
                .message("Thanh toán tiền mặt thành công")
                .build();
    }

    /**
     * Ghi nhận thu tiền mặt tại quầy cho phí khám bệnh (Dành cho nội bộ khi tiếp nhận trực tiếp).
     * Tạo Invoice loại EXAMINATION với trạng thái PAID, tạo PaymentTransaction CASH SUCCESS,
     * và bắn Kafka event PaymentSuccessEvent để đồng bộ hệ thống.
     */
    @Transactional
    public Long recordCashExamination(Long appointmentId, Long patientId, BigDecimal amount) {
        // Kiểm tra xem đã có hóa đơn phí khám đã thanh toán chưa
        Optional<Invoice> paidInvoice = invoiceRepository.findByAppointmentIdAndTypeAndStatus(
                appointmentId, InvoiceType.EXAMINATION, InvoiceStatus.PAID);
        if (paidInvoice.isPresent()) {
            return paidInvoice.get().getId();
        }

        Instant now = Instant.now();
        Invoice invoice = Invoice.builder()
                .appointmentId(appointmentId)
                .patientId(patientId)
                .type(InvoiceType.EXAMINATION)
                .amount(amount != null ? amount : BigDecimal.ZERO)
                .status(InvoiceStatus.PAID)
                .createdAt(now)
                .paidAt(now)
                .build();
        invoice = invoiceRepository.save(invoice);

        String txnRef = "CASH-EXAM-" + invoice.getId() + "-" + System.currentTimeMillis();
        PaymentTransaction transaction = PaymentTransaction.builder()
                .invoiceId(invoice.getId())
                .txnRef(txnRef)
                .amount(invoice.getAmount())
                .paymentMethod("CASH")
                .status(TransactionStatus.SUCCESS)
                .vnpTransactionNo("CASH_COUNTER")
                .payDate(DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(now))
                .createdAt(now)
                .build();
        paymentTransactionRepository.save(transaction);

        // Bắn Kafka Outbox Event để đồng bộ sang các service
        PaymentSuccessEvent event = PaymentSuccessEvent.builder()
                .appointmentId(appointmentId)
                .invoiceId(invoice.getId())
                .patientId(patientId)
                .type(InvoiceType.EXAMINATION.name())
                .amount(invoice.getAmount())
                .txnRef(txnRef)
                .vnpTransactionNo("CASH_COUNTER")
                .paidAt(now)
                .build();
        paymentKafkaProducer.sendPaymentSuccessEvent(event);

        log.info("Recorded cash examination payment for appointmentId={}, invoiceId={}, amount={}",
                appointmentId, invoice.getId(), invoice.getAmount());

        return invoice.getId();
    }

    public String getResponseMessage(String responseCode) {
        return switch (responseCode) {
            case "00" -> "Giao dịch thành công";
            case "07" -> "Giao dịch bị nghi ngờ bất thường";
            case "09" -> "Thẻ/Tài khoản chưa đăng ký Internet Banking";
            case "10" -> "Xác thực thông tin thẻ/tài khoản sai quá 3 lần";
            case "11" -> "Đã hết hạn chờ thanh toán";
            case "12" -> "Thẻ/Tài khoản bị khóa";
            case "13" -> "Nhập sai mã OTP";
            case "24" -> "Khách hàng hủy giao dịch";
            case "51" -> "Tài khoản không đủ số dư";
            case "65" -> "Vượt hạn mức giao dịch trong ngày";
            case "75" -> "Ngân hàng đang bảo trì";
            case "79" -> "Nhập sai mật khẩu thanh toán quá số lần quy định";
            case "99" -> "Lỗi không xác định";
            default -> "Giao dịch không thành công, mã phản hồi: " + responseCode;
        };
    }
}
