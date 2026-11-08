package com.example.payment.dto.response;

import com.example.payment.util.InvoiceStatus;
import com.example.payment.util.InvoiceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvoiceResponse {

    private Long id;
    private Long appointmentId;
    private Long patientId;
    private InvoiceType type;
    private BigDecimal amount;
    private InvoiceStatus status;
    private Instant createdAt;
    private Instant paidAt;
    private List<ServiceItemResponse> services;
}
