package com.example.payment.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargeResponse {

    private Long appointmentId;
    private Long patientId;
    private Long invoiceId;
    private String type;
    private BigDecimal amount;
    private List<Long> itemIds;
    private java.time.Instant expiresAt;
}
