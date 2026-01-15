package com.example.order.service;

import com.example.order.entity.OrderStatus;
import com.example.order.event.PaymentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private PaymentEventConsumer paymentEventConsumer;

    @Test
    @DisplayName("결제 성공 이벤트 수신 시 주문 상태를 CONFIRMED로 변경한다")
    void updatesOrderToConfirmedOnSuccess() {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentId(1L);
        event.setOrderId(1L);
        event.setAmount(new BigDecimal("2500000"));
        event.setStatus("SUCCESS");

        paymentEventConsumer.handlePaymentEvent(event);

        verify(orderService).updateOrderStatus(1L, OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("결제 실패 이벤트 수신 시 주문 상태를 CANCELLED로 변경한다")
    void updatesOrderToCancelledOnFailure() {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentId(2L);
        event.setOrderId(1L);
        event.setAmount(new BigDecimal("2500000"));
        event.setStatus("FAILED");

        paymentEventConsumer.handlePaymentEvent(event);

        verify(orderService).updateOrderStatus(1L, OrderStatus.CANCELLED);
    }
}
