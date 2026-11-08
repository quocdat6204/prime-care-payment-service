package com.example.payment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private String paymentUrl;
    private String orderId;
    private Long invoiceId;
    private String txnRef;
    private Long amount;
    private String message;
}
