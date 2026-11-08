package com.example.payment.controller;

import com.example.payment.dto.response.ApiResponse;
import com.example.payment.dto.response.statistic.PaymentSummaryResponse;
import com.example.payment.dto.response.statistic.RevenueOverviewResponse;
import com.example.payment.dto.response.statistic.RevenueResponse;
import com.example.payment.service.PaymentStatisticService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller nội bộ phục vụ statistic-service gọi qua OpenFeign.
 */
@RestController
@RequestMapping("/internal/payment/statistics")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalStatisticController {

    PaymentStatisticService paymentStatisticService;

    @GetMapping("/revenue-overview")
    public ApiResponse<RevenueOverviewResponse> getRevenueOverview(
            @RequestParam int month,
            @RequestParam int year) {
        return ApiResponse.<RevenueOverviewResponse>builder()
                .result(paymentStatisticService.getRevenueOverview(month, year))
                .build();
    }

    @GetMapping("/revenue")
    public ApiResponse<RevenueResponse> getRevenue(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam String groupBy) {
        return ApiResponse.<RevenueResponse>builder()
                .result(paymentStatisticService.getRevenue(from, to, groupBy))
                .build();
    }

    @GetMapping("/summary")
    public ApiResponse<PaymentSummaryResponse> getPaymentSummary(
            @RequestParam String from,
            @RequestParam String to) {
        return ApiResponse.<PaymentSummaryResponse>builder()
                .result(paymentStatisticService.getPaymentSummary(from, to))
                .build();
    }
}
