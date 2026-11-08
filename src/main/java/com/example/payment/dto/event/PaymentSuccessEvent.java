package com.example.payment.dto.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSuccessEvent {

    private Long appointmentId;
    private Long invoiceId;
    private Long patientId;
    private String type; // "EXAMINATION" or "SERVICES"
    private BigDecimal amount;
    private String txnRef;
    private String vnpTransactionNo;
    private Instant paidAt;
}
