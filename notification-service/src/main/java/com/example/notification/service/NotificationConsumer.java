package com.example.notification.service;

import com.example.notification.event.NotificationSentEvent;
import com.example.notification.event.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    private static final String NOTIFICATION_EVENTS_TOPIC = "notification-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public NotificationConsumer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "payment-events", groupId = "notification-service-group")
    public void handlePaymentEvent(PaymentEvent event) {
        log.info("==============================================");
        log.info("NOTIFICATION SERVICE - Received PaymentEvent");
        log.info("Order ID: {}", event.getOrderId());
        log.info("Payment ID: {}", event.getPaymentId());
        log.info("Amount: {}", event.getAmount());
        log.info("Status: {}", event.getStatus());

        String message;
        if ("SUCCESS".equals(event.getStatus())) {
            message = String.format("[SUCCESS] 주문 #%d 결제가 완료되었습니다. 결제 금액: %s원",
                    event.getOrderId(), event.getAmount());
            log.info("SENDING NOTIFICATION: {}", message);
        } else {
            message = String.format("[FAILED] 주문 #%d 결제가 실패하였습니다. 다시 시도해주세요.",
                    event.getOrderId());
            log.warn("SENDING NOTIFICATION: {}", message);
        }
        log.info("==============================================");

        NotificationSentEvent notificationEvent = new NotificationSentEvent(
                event.getOrderId(), message, "LOG"
        );
        kafkaTemplate.send(NOTIFICATION_EVENTS_TOPIC, String.valueOf(event.getOrderId()), notificationEvent);
        log.info("Published NotificationSentEvent to Kafka: orderId={}", event.getOrderId());
    }
}
