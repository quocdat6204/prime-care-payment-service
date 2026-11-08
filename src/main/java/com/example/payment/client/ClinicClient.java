package com.example.payment.client;

import com.example.payment.dto.request.BindServiceInvoiceRequest;
import com.example.payment.dto.response.ApiResponse;
import com.example.payment.dto.response.ChargeResponse;
import com.example.payment.dto.response.ServiceItemResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "clinic-service")
public interface ClinicClient {

    @GetMapping("/internal/clinic/appointments/{id}/examination-charge")
    ApiResponse<ChargeResponse> getExaminationCharge(@PathVariable("id") Long id);

    @PostMapping("/internal/clinic/appointments/{id}/service-invoices")
    ApiResponse<ChargeResponse> bindServiceInvoice(
            @PathVariable("id") Long id,
            @RequestBody BindServiceInvoiceRequest request
    );

    @GetMapping("/internal/clinic/appointments/{id}/services")
    ApiResponse<List<ServiceItemResponse>> getAppointmentServices(@PathVariable("id") Long id);
}
