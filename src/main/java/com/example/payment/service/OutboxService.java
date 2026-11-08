package com.example.payment.service;

import com.example.payment.dto.event.PaymentSuccessEvent;
import com.example.payment.model.OutboxEvent;
import com.example.payment.repository.OutboxEventRepository;
import com.example.payment.util.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${payment.kafka.topic.payment-events:payment-events}")
    private String topicName;

    /**
     * Lưu sự kiện thanh toán vào Outbox trong cùng Transaction nghiệp vụ,
     * sau đó tự động thử gửi Kafka ngay khi Transaction commit thành công.
     */
    public void saveAndPublish(PaymentSuccessEvent event) {
        OutboxEvent outboxEvent = saveEvent(event);

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishEvent(outboxEvent.getId());
                }
            });
        } else {
            publishEvent(outboxEvent.getId());
        }
    }

    /**
     * Ghi bản ghi vào bảng outbox_events.
     */
    public OutboxEvent saveEvent(PaymentSuccessEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(String.valueOf(event.getAppointmentId()))
                    .eventType("PAYMENT_SUCCESS")
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .build();

            OutboxEvent saved = outboxEventRepository.save(outboxEvent);
            log.info("Saved OutboxEvent id={} for invoiceId={}", saved.getId(), event.getInvoiceId());
            return saved;
        } catch (Exception e) {
            log.error("Failed to serialize and save OutboxEvent for invoiceId={}: {}", event.getInvoiceId(), e.getMessage());
            throw new RuntimeException("Cannot save outbox event", e);
        }
    }

    /**
     * Thử gửi event ra Kafka.
     * Sử dụng REQUIRES_NEW để độc lập với transaction khác, cập nhật trạng thái SENT hoặc lỗi.
     * Trả về true nếu gửi thành công, false nếu Kafka vẫn chưa sẵn sàng.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publishEvent(Long outboxEventId) {
        OutboxEvent outboxEvent = outboxEventRepository.findById(outboxEventId).orElse(null);
        if (outboxEvent == null || outboxEvent.getStatus() == OutboxStatus.SENT) {
            return true;
        }

        try {
            PaymentSuccessEvent event = objectMapper.readValue(outboxEvent.getPayload(), PaymentSuccessEvent.class);

            // Gửi đồng bộ có timeout 3s để phát hiện Kafka sống hay chết
            kafkaTemplate.send(topicName, outboxEvent.getAggregateId(), event).get(3, TimeUnit.SECONDS);

            outboxEvent.setStatus(OutboxStatus.SENT);
            outboxEvent.setProcessedAt(Instant.now());
            outboxEvent.setLastError(null);
            outboxEventRepository.save(outboxEvent);
            log.info("Successfully published OutboxEvent id={} to Kafka topic [{}]", outboxEvent.getId(), topicName);
            return true;
        } catch (Exception e) {
            int retries = outboxEvent.getRetryCount() + 1;
            outboxEvent.setRetryCount(retries);
            outboxEvent.setLastError(e.getMessage());
            // Giữ nguyên trạng thái PENDING để không bao giờ bỏ rơi giao dịch của khách hàng.
            // Scheduler sẽ kiên trì thử lại mỗi 15 giây cho đến khi Kafka hoạt động trở lại.
            outboxEventRepository.save(outboxEvent);
            log.warn("Failed to publish OutboxEvent id={} to Kafka (attempt #{}): {}. Kept as PENDING.",
                    outboxEvent.getId(), retries, e.getMessage());
            return false;
        }
    }

    /**
     * Scheduler định kỳ quét các bản ghi PENDING (do Kafka chết trước đó) và thử gửi lại.
     */
    @Scheduled(fixedDelay = 15000)
    public void retryPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Outbox scheduler: Found {} pending events to retry...", pendingEvents.size());
        for (OutboxEvent event : pendingEvents) {
            boolean success = publishEvent(event.getId());
            if (!success) {
                // Nếu 1 event thất bại do Kafka chết, tạm dừng batch hiện tại để tránh timeout liên hoàn.
                // Chu kỳ 15 giây tiếp theo sẽ tự động thử lại.
                log.warn("Kafka is still unavailable. Pausing current retry batch, will retry in 15 seconds.");
                break;
            }
        }
    }

    /**
     * Tự động dọn dẹp các OutboxEvent đã gửi thành công (SENT) sau 7 ngày để tránh phình to database.
     * Chạy định kỳ vào 2:00 sáng mỗi ngày.
     */
    @Scheduled(cron = "${payment.outbox.cleanup-cron:0 0 2 * * ?}")
    public void purgeOldSentEvents() {
        Instant sevenDaysAgo = Instant.now().minus(Duration.ofDays(7));
        int deleted = outboxEventRepository.deleteByStatusAndProcessedAtBefore(OutboxStatus.SENT, sevenDaysAgo);
        if (deleted > 0) {
            log.info("Outbox cleanup: Purged {} SENT events older than 7 days.", deleted);
        }
    }
}

