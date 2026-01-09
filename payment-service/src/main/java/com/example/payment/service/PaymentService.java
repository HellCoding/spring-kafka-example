package com.example.payment.service;

import com.example.payment.entity.Payment;
import com.example.payment.entity.PaymentStatus;
import com.example.payment.event.OrderCreatedEvent;
import com.example.payment.event.PaymentEvent;
import com.example.payment.exception.PaymentNotFoundException;
import com.example.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 주문 이벤트를 수신하여 결제를 처리하고 결과를 Kafka로 발행하는 서비스.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String PAYMENT_EVENTS_TOPIC = "payment-events";

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentService(PaymentRepository paymentRepository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.paymentRepository = paymentRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "order-events", groupId = "payment-service-group")
    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent: orderId={}, amount={}", event.getOrderId(), event.getTotalAmount());

        // 결제 처리 시뮬레이션 (70% 성공, 30% 실패)
        boolean paymentSuccess = ThreadLocalRandom.current().nextInt(100) < 70;

        Payment payment = new Payment();
        payment.setOrderId(event.getOrderId());
        payment.setAmount(event.getTotalAmount());
        payment.setStatus(paymentSuccess ? PaymentStatus.SUCCESS : PaymentStatus.FAILED);

        Payment savedPayment = paymentRepository.save(payment);
        log.info("Payment processed: id={}, orderId={}, status={}",
                savedPayment.getId(), savedPayment.getOrderId(), savedPayment.getStatus());

        PaymentEvent paymentEvent = new PaymentEvent(
                savedPayment.getId(),
                savedPayment.getOrderId(),
                savedPayment.getAmount(),
                savedPayment.getStatus().name()
        );

        kafkaTemplate.send(PAYMENT_EVENTS_TOPIC, String.valueOf(event.getOrderId()), paymentEvent);
        log.info("Published PaymentEvent to Kafka: orderId={}, status={}", event.getOrderId(), paymentEvent.getStatus());
    }

    public Payment getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException(orderId));
    }
}
