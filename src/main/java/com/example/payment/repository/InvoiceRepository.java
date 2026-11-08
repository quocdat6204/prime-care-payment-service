package com.example.payment.repository;

import com.example.payment.model.Invoice;
import com.example.payment.util.InvoiceStatus;
import com.example.payment.util.InvoiceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByAppointmentIdAndTypeAndStatus(Long appointmentId, InvoiceType type, InvoiceStatus status);

    List<Invoice> findByAppointmentId(Long appointmentId);

    List<Invoice> findByAppointmentIdAndTypeAndStatusOrderByCreatedAtDesc(Long appointmentId, InvoiceType type, InvoiceStatus status);

    Page<Invoice> findByStatus(InvoiceStatus status, Pageable pageable);

    List<Invoice> findByPaidAtBetweenAndStatus(java.time.Instant start, java.time.Instant end, InvoiceStatus status);

    List<Invoice> findByCreatedAtBetween(java.time.Instant start, java.time.Instant end);
}
