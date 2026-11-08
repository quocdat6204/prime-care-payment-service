package com.example.payment.dto.request;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BindServiceInvoiceRequest {

    private Long invoiceId;
    private List<Long> itemIds;
}
