package com.example.payment.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentPaymentCheckResponse {
    private Long appointmentId;
    private Long invoiceId;
    private BigDecimal amount;
    private Instant paidAt;
    private String status; // "PAID", "UNPAID"
}

