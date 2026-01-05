package com.example.order.service;

import com.example.order.entity.OrderStatus;
import com.example.order.event.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final OrderService orderService;

    public PaymentEventConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = "payment-events", groupId = "order-service-group")
    public void handlePaymentEvent(PaymentEvent event) {
        log.info("Received PaymentEvent: orderId={}, status={}", event.getOrderId(), event.getStatus());

        if ("SUCCESS".equals(event.getStatus())) {
            orderService.updateOrderStatus(event.getOrderId(), OrderStatus.CONFIRMED);
        } else {
            orderService.updateOrderStatus(event.getOrderId(), OrderStatus.CANCELLED);
        }
    }
}
