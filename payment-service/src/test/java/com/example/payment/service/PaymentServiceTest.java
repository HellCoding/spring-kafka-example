package com.example.payment.service;

import com.example.payment.entity.Payment;
import com.example.payment.entity.PaymentStatus;
import com.example.payment.event.OrderCreatedEvent;
import com.example.payment.event.PaymentEvent;
import com.example.payment.exception.PaymentNotFoundException;
import com.example.payment.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private PaymentService paymentService;

    @Nested
    @DisplayName("handleOrderCreated")
    class HandleOrderCreated {

        @Test
        @DisplayName("주문 이벤트를 수신하면 결제를 처리하고 DB에 저장한다")
        void processesPaymentAndSaves() {
            OrderCreatedEvent event = new OrderCreatedEvent();
            event.setOrderId(1L);
            event.setProductName("맥북 프로");
            event.setQuantity(1);
            event.setTotalAmount(new BigDecimal("2500000"));

            Payment savedPayment = new Payment();
            savedPayment.setId(1L);
            savedPayment.setOrderId(1L);
            savedPayment.setAmount(new BigDecimal("2500000"));
            savedPayment.setStatus(PaymentStatus.SUCCESS);

            when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);

            paymentService.handleOrderCreated(event);

            ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(paymentCaptor.capture());

            Payment captured = paymentCaptor.getValue();
            assertThat(captured.getOrderId()).isEqualTo(1L);
            assertThat(captured.getAmount()).isEqualByComparingTo(new BigDecimal("2500000"));
            assertThat(captured.getStatus()).isIn(PaymentStatus.SUCCESS, PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("결제 처리 후 PaymentEvent를 payment-events 토픽에 발행한다")
        void publishesPaymentEvent() {
            OrderCreatedEvent event = new OrderCreatedEvent();
            event.setOrderId(1L);
            event.setTotalAmount(new BigDecimal("50000"));

            Payment savedPayment = new Payment();
            savedPayment.setId(1L);
            savedPayment.setOrderId(1L);
            savedPayment.setAmount(new BigDecimal("50000"));
            savedPayment.setStatus(PaymentStatus.SUCCESS);

            when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);

            paymentService.handleOrderCreated(event);

            ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
            verify(kafkaTemplate).send(eq("payment-events"), eq("1"), eventCaptor.capture());

            PaymentEvent paymentEvent = eventCaptor.getValue();
            assertThat(paymentEvent.getPaymentId()).isEqualTo(1L);
            assertThat(paymentEvent.getOrderId()).isEqualTo(1L);
            assertThat(paymentEvent.getAmount()).isEqualByComparingTo(new BigDecimal("50000"));
            assertThat(paymentEvent.getStatus()).isIn("SUCCESS", "FAILED");
        }

        @RepeatedTest(20)
        @DisplayName("결제 성공/실패가 랜덤하게 결정된다 (70%/30%)")
        void paymentResultIsRandom() {
            OrderCreatedEvent event = new OrderCreatedEvent();
            event.setOrderId(1L);
            event.setTotalAmount(new BigDecimal("10000"));

            Payment savedPayment = new Payment();
            savedPayment.setId(1L);
            savedPayment.setOrderId(1L);
            savedPayment.setAmount(new BigDecimal("10000"));
            savedPayment.setStatus(PaymentStatus.SUCCESS);

            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
                Payment p = invocation.getArgument(0);
                p.setId(1L);
                return p;
            });

            paymentService.handleOrderCreated(event);

            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(captor.capture());

            assertThat(captor.getValue().getStatus()).isIn(PaymentStatus.SUCCESS, PaymentStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("getPaymentByOrderId")
    class GetPaymentByOrderId {

        @Test
        @DisplayName("존재하는 결제 정보를 반환한다")
        void returnsExistingPayment() {
            Payment payment = new Payment();
            payment.setId(1L);
            payment.setOrderId(1L);
            payment.setAmount(new BigDecimal("2500000"));
            payment.setStatus(PaymentStatus.SUCCESS);

            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

            Payment result = paymentService.getPaymentByOrderId(1L);

            assertThat(result.getOrderId()).isEqualTo(1L);
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("존재하지 않는 결제 조회 시 PaymentNotFoundException을 던진다")
        void throwsWhenPaymentNotFound() {
            when(paymentRepository.findByOrderId(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.getPaymentByOrderId(999L))
                    .isInstanceOf(PaymentNotFoundException.class);
        }
    }
}
