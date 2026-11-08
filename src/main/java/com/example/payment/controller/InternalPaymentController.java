package com.example.payment.controller;

import com.example.payment.dto.response.ApiResponse;
import com.example.payment.dto.response.AppointmentPaymentCheckResponse;
import com.example.payment.model.Invoice;
import com.example.payment.repository.InvoiceRepository;
import com.example.payment.service.PaymentService;
import com.example.payment.util.InvoiceStatus;
import com.example.payment.util.InvoiceType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/internal/payment")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final InvoiceRepository invoiceRepository;
    private final PaymentService paymentService;

    @GetMapping("/appointments/{appointmentId}/examination-status")
    public ApiResponse<AppointmentPaymentCheckResponse> getExaminationPaymentStatus(
            @PathVariable Long appointmentId) {

        List<Invoice> invoices = invoiceRepository.findByAppointmentIdAndTypeAndStatusOrderByCreatedAtDesc(
                appointmentId, InvoiceType.EXAMINATION, InvoiceStatus.PAID);

        if (!invoices.isEmpty()) {
            Invoice invoice = invoices.get(0);
            return ApiResponse.<AppointmentPaymentCheckResponse>builder()
                    .result(AppointmentPaymentCheckResponse.builder()
                            .appointmentId(appointmentId)
                            .invoiceId(invoice.getId())
                            .amount(invoice.getAmount())
                            .paidAt(invoice.getPaidAt())
                            .status("PAID")
                            .build())
                    .build();
        }

        return ApiResponse.<AppointmentPaymentCheckResponse>builder()
                .result(AppointmentPaymentCheckResponse.builder()
                        .appointmentId(appointmentId)
                        .status("UNPAID")
                        .build())
                .build();
    }

    @PostMapping("/appointments/{appointmentId}/cash-examination")
    public ApiResponse<Long> recordCashExamination(
            @PathVariable Long appointmentId,
            @RequestParam Long patientId,
            @RequestParam BigDecimal amount) {

        Long invoiceId = paymentService.recordCashExamination(appointmentId, patientId, amount);
        return ApiResponse.<Long>builder()
                .result(invoiceId)
                .build();
    }
}

