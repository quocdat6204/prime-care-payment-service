package com.example.payment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceItemResponse {

    private Long id;
    private Long appointmentId;
    private Long serviceId;
    private Long invoiceId;
    private String serviceName;
    private String serviceType;
    private BigDecimal unitPrice;
    private String roomNumber;
    private String status;
    private Instant paidAt;
    private String resultUrl;
    private String note;
}
