package com.example.payment.producer;

import com.example.payment.dto.event.PaymentSuccessEvent;
import com.example.payment.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentKafkaProducer {

    private final OutboxService outboxService;

    public void sendPaymentSuccessEvent(PaymentSuccessEvent event) {
        log.info("Processing PaymentSuccessEvent via Transactional Outbox for invoiceId={}, appointmentId={}",
                event.getInvoiceId(), event.getAppointmentId());
        outboxService.saveAndPublish(event);
    }
}
