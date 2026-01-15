package com.example.notification.service;

import com.example.notification.event.NotificationSentEvent;
import com.example.notification.event.PaymentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private NotificationConsumer notificationConsumer;

    @Test
    @DisplayName("결제 성공 시 성공 알림 메시지를 생성하고 notification-events 토픽에 발행한다")
    void sendsSuccessNotification() {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentId(1L);
        event.setOrderId(1L);
        event.setAmount(new BigDecimal("2500000"));
        event.setStatus("SUCCESS");

        notificationConsumer.handlePaymentEvent(event);

        ArgumentCaptor<NotificationSentEvent> captor = ArgumentCaptor.forClass(NotificationSentEvent.class);
        verify(kafkaTemplate).send(eq("notification-events"), eq("1"), captor.capture());

        NotificationSentEvent notification = captor.getValue();
        assertThat(notification.getOrderId()).isEqualTo(1L);
        assertThat(notification.getMessage()).contains("[SUCCESS]");
        assertThat(notification.getMessage()).contains("주문 #1");
        assertThat(notification.getMessage()).contains("2500000");
        assertThat(notification.getChannel()).isEqualTo("LOG");
    }

    @Test
    @DisplayName("결제 실패 시 실패 알림 메시지를 생성하고 notification-events 토픽에 발행한다")
    void sendsFailureNotification() {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentId(2L);
        event.setOrderId(3L);
        event.setAmount(new BigDecimal("50000"));
        event.setStatus("FAILED");

        notificationConsumer.handlePaymentEvent(event);

        ArgumentCaptor<NotificationSentEvent> captor = ArgumentCaptor.forClass(NotificationSentEvent.class);
        verify(kafkaTemplate).send(eq("notification-events"), eq("3"), captor.capture());

        NotificationSentEvent notification = captor.getValue();
        assertThat(notification.getOrderId()).isEqualTo(3L);
        assertThat(notification.getMessage()).contains("[FAILED]");
        assertThat(notification.getMessage()).contains("주문 #3");
        assertThat(notification.getChannel()).isEqualTo("LOG");
    }
}
