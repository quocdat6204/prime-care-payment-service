package com.example.payment.service;

import com.example.payment.dto.response.statistic.PaymentSummaryResponse;
import com.example.payment.dto.response.statistic.RevenueOverviewResponse;
import com.example.payment.dto.response.statistic.RevenueResponse;
import com.example.payment.model.Invoice;
import com.example.payment.model.PaymentTransaction;
import com.example.payment.repository.InvoiceRepository;
import com.example.payment.repository.PaymentTransactionRepository;
import com.example.payment.util.InvoiceStatus;
import com.example.payment.util.InvoiceType;
import com.example.payment.util.TransactionStatus;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@Transactional(readOnly = true)
public class PaymentStatisticService {

    InvoiceRepository invoiceRepository;
    PaymentTransactionRepository paymentTransactionRepository;

    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

    private Instant parseStartOfDay(String dateStr) {
        return LocalDate.parse(dateStr).atStartOfDay(DEFAULT_ZONE).toInstant();
    }

    private Instant parseEndOfDay(String dateStr) {
        return LocalDate.parse(dateStr).plusDays(1).atStartOfDay(DEFAULT_ZONE).toInstant();
    }

    public RevenueOverviewResponse getRevenueOverview(int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        Instant start = ym.atDay(1).atStartOfDay(DEFAULT_ZONE).toInstant();
        Instant end = ym.plusMonths(1).atDay(1).atStartOfDay(DEFAULT_ZONE).toInstant();

        List<Invoice> list = invoiceRepository.findByPaidAtBetweenAndStatus(start, end, InvoiceStatus.PAID);

        long examRev = list.stream()
                .filter(i -> i.getType() == InvoiceType.EXAMINATION)
                .map(Invoice::getAmount)
                .filter(Objects::nonNull)
                .mapToLong(BigDecimal::longValue)
                .sum();

        long serviceRev = list.stream()
                .filter(i -> i.getType() == InvoiceType.SERVICES)
                .map(Invoice::getAmount)
                .filter(Objects::nonNull)
                .mapToLong(BigDecimal::longValue)
                .sum();

        return RevenueOverviewResponse.builder()
                .total(examRev + serviceRev)
                .examination(examRev)
                .services(serviceRev)
                .build();
    }

    public RevenueResponse getRevenue(String from, String to, String groupBy) {
        Instant start = parseStartOfDay(from);
        Instant end = parseEndOfDay(to);

        List<Invoice> list = invoiceRepository.findByPaidAtBetweenAndStatus(start, end, InvoiceStatus.PAID);
        DateTimeFormatter formatter = "MONTH".equalsIgnoreCase(groupBy)
                ? DateTimeFormatter.ofPattern("yyyy-MM").withZone(DEFAULT_ZONE)
                : DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(DEFAULT_ZONE);

        Map<String, List<Invoice>> grouped = list.stream()
                .collect(Collectors.groupingBy(i -> formatter.format(i.getPaidAt())));

        List<RevenueResponse.RevenueItem> items = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    String label = e.getKey();
                    List<Invoice> invoices = e.getValue();
                    long exam = invoices.stream()
                            .filter(i -> i.getType() == InvoiceType.EXAMINATION)
                            .map(Invoice::getAmount)
                            .filter(Objects::nonNull)
                            .mapToLong(BigDecimal::longValue)
                            .sum();
                    long srv = invoices.stream()
                            .filter(i -> i.getType() == InvoiceType.SERVICES)
                            .map(Invoice::getAmount)
                            .filter(Objects::nonNull)
                            .mapToLong(BigDecimal::longValue)
                            .sum();

                    return RevenueResponse.RevenueItem.builder()
                            .label(label)
                            .examination(exam)
                            .services(srv)
                            .total(exam + srv)
                            .build();
                })
                .collect(Collectors.toList());

        long grandTotal = items.stream().mapToLong(RevenueResponse.RevenueItem::getTotal).sum();

        return RevenueResponse.builder()
                .from(from)
                .to(to)
                .groupBy(groupBy)
                .totalRevenue(grandTotal)
                .data(items)
                .build();
    }

    public PaymentSummaryResponse getPaymentSummary(String from, String to) {
        Instant start = parseStartOfDay(from);
        Instant end = parseEndOfDay(to);

        List<Invoice> invoices = invoiceRepository.findByCreatedAtBetween(start, end);
        long totalInvoices = invoices.size();
        long paidInvoices = invoices.stream().filter(i -> i.getStatus() == InvoiceStatus.PAID).count();
        long pendingInvoices = invoices.stream().filter(i -> i.getStatus() == InvoiceStatus.PENDING).count();
        long cancelledInvoices = invoices.stream().filter(i -> i.getStatus() == InvoiceStatus.CANCELLED || i.getStatus() == InvoiceStatus.EXPIRED).count();

        double successRate = totalInvoices > 0
                ? Math.round(((double) paidInvoices / totalInvoices * 100.0) * 100.0) / 100.0
                : 0.0;

        long totalRevenue = invoices.stream()
                .filter(i -> i.getStatus() == InvoiceStatus.PAID)
                .map(Invoice::getAmount)
                .filter(Objects::nonNull)
                .mapToLong(BigDecimal::longValue)
                .sum();

        // Thống kê theo loại hoá đơn (InvoiceType)
        Map<String, PaymentSummaryResponse.TypeSummary> byType = new HashMap<>();
        for (InvoiceType type : InvoiceType.values()) {
            List<Invoice> ofType = invoices.stream()
                    .filter(i -> i.getType() == type && i.getStatus() == InvoiceStatus.PAID)
                    .collect(Collectors.toList());
            long count = ofType.size();
            long amount = ofType.stream()
                    .map(Invoice::getAmount)
                    .filter(Objects::nonNull)
                    .mapToLong(BigDecimal::longValue)
                    .sum();
            byType.put(type.name(), PaymentSummaryResponse.TypeSummary.builder()
                    .count(count)
                    .amount(amount)
                    .build());
        }

        // Thống kê ngân hàng từ PaymentTransaction
        List<PaymentTransaction> txns = paymentTransactionRepository.findByCreatedAtBetween(start, end);
        List<PaymentTransaction> successTxns = txns.stream()
                .filter(t -> t.getStatus() == TransactionStatus.SUCCESS && t.getBankCode() != null && !t.getBankCode().isBlank())
                .collect(Collectors.toList());

        long totalSuccessTxn = successTxns.size();
        Map<String, Long> bankCounts = successTxns.stream()
                .collect(Collectors.groupingBy(PaymentTransaction::getBankCode, Collectors.counting()));

        List<PaymentSummaryResponse.BankStats> topBanks = bankCounts.entrySet().stream()
                .map(e -> {
                    double pct = totalSuccessTxn > 0
                            ? Math.round(((double) e.getValue() / totalSuccessTxn * 100.0) * 100.0) / 100.0
                            : 0.0;
                    return PaymentSummaryResponse.BankStats.builder()
                            .bankCode(e.getKey())
                            .count(e.getValue())
                            .percentage(pct)
                            .build();
                })
                .sorted(Comparator.comparingLong(PaymentSummaryResponse.BankStats::getCount).reversed())
                .collect(Collectors.toList());

        return PaymentSummaryResponse.builder()
                .totalInvoices(totalInvoices)
                .paidInvoices(paidInvoices)
                .pendingInvoices(pendingInvoices)
                .cancelledInvoices(cancelledInvoices)
                .paymentSuccessRate(successRate)
                .totalRevenue(totalRevenue)
                .byType(byType)
                .topBanks(topBanks)
                .build();
    }
}
