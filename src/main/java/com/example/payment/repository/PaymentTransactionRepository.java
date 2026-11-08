package com.example.payment.repository;

import com.example.payment.model.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByTxnRef(String txnRef);

    List<PaymentTransaction> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);

    List<PaymentTransaction> findByCreatedAtBetween(java.time.Instant start, java.time.Instant end);
}
