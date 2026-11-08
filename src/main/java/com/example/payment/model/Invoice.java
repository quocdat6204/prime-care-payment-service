package com.example.payment.model;

import com.example.payment.util.InvoiceStatus;
import com.example.payment.util.InvoiceType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "invoices",
        indexes = {
                @Index(name = "idx_invoices_appointment_type", columnList = "appointment_id, type"),
                @Index(name = "idx_invoices_patient", columnList = "patient_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "appointment_id", nullable = false)
    Long appointmentId;

    @Column(name = "patient_id", nullable = false)
    Long patientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    InvoiceType type;

    @Column(name = "amount", nullable = false, precision = 18, scale = 0)
    BigDecimal amount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    InvoiceStatus status = InvoiceStatus.PENDING;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt = Instant.now();

    @Column(name = "paid_at")
    Instant paidAt;
}
