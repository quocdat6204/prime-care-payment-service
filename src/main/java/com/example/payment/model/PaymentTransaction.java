package com.example.payment.model;

import com.example.payment.util.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "transactions",
        indexes = {
                @Index(name = "idx_transactions_invoice", columnList = "invoice_id"),
                @Index(name = "idx_transactions_txn_ref", columnList = "txn_ref", unique = true)
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "invoice_id", nullable = false)
    Long invoiceId;

    @Column(name = "txn_ref", nullable = false, unique = true, length = 100)
    String txnRef;

    @Column(name = "amount", nullable = false, precision = 18, scale = 0)
    BigDecimal amount;

    @Builder.Default
    @Column(name = "payment_method", nullable = false, length = 30)
    String paymentMethod = "VNPAY";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "vnp_transaction_no", length = 100)
    String vnpTransactionNo;

    @Column(name = "bank_code", length = 50)
    String bankCode;

    @Column(name = "pay_date", length = 30)
    String payDate;

    @Column(name = "response_code", length = 10)
    String responseCode;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt = Instant.now();
}
