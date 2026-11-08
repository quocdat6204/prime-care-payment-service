package com.example.payment.dto.request;

import com.example.payment.util.InvoiceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    /** ID của lịch hẹn khám bệnh */
    private Long appointmentId;

    /** ID của hóa đơn (tùy chọn - dùng khi thanh toán lại hóa đơn đã tạo) */
    private Long invoiceId;

    /** Loại thanh toán: EXAMINATION hoặc SERVICES */
    private InvoiceType type;

    /** Danh sách ID dịch vụ chỉ định (khi type = SERVICES) */
    private List<Long> serviceItemIds;

    /** Số tiền (VND). Nếu để trống hoặc null, sẽ tự động lấy và đối soát từ clinic-service */
    private Long amount;

    /** Mã tham chiếu tùy chọn (hỗ trợ legacy/test) */
    private String orderId;
}
