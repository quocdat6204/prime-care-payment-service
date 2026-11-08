package com.example.payment.dto.response.statistic;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RevenueResponse {

    String from;
    String to;
    String groupBy;
    long totalRevenue;
    List<RevenueItem> data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevenueItem {
        String label;
        long examination;
        long services;
        long total;
    }
}
