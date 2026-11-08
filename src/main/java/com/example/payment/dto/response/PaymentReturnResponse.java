package com.example.payment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReturnResponse {

    private boolean success;
    private String message;
    private String orderId;
    private Long invoiceId;
    private String transactionId;
    private Long amount;
    private String responseCode;
    private String responseMessage;
}
