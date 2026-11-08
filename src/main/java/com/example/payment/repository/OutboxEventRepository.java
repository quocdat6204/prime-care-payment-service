package com.example.payment.repository;

import com.example.payment.model.OutboxEvent;
import com.example.payment.util.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.processedAt <= :cutoff")
    int deleteByStatusAndProcessedAtBefore(OutboxStatus status, Instant cutoff);
}
