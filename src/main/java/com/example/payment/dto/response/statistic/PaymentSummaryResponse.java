package com.example.payment.dto.response.statistic;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentSummaryResponse {

    long totalInvoices;
    long paidInvoices;
    long pendingInvoices;
    long cancelledInvoices;
    double paymentSuccessRate;
    long totalRevenue;
    Map<String, TypeSummary> byType;
    List<BankStats> topBanks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypeSummary {
        long count;
        long amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankStats {
        String bankCode;
        long count;
        double percentage;
    }
}
