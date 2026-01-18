package com.example.payment.service;

import com.example.payment.entity.Payment;
import com.example.payment.entity.PaymentStatus;
import com.example.payment.event.OrderCreatedEvent;
import com.example.payment.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@ActiveProfiles("integration")
class PaymentIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("payment_db")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAll();
    }

    @Test
    @DisplayName("OrderCreatedEvent를 수신하면 결제를 처리하고 DB에 저장한다")
    void processesOrderEventAndSavesPayment() {
        OrderCreatedEvent orderEvent = new OrderCreatedEvent();
        orderEvent.setOrderId(100L);
        orderEvent.setProductName("갤럭시 S24");
        orderEvent.setQuantity(1);
        orderEvent.setTotalAmount(new BigDecimal("1200000"));

        kafkaTemplate.send("order-events", "100", orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<Payment> payment = paymentRepository.findByOrderId(100L);
            assertThat(payment).isPresent();
            assertThat(payment.get().getAmount()).isEqualByComparingTo(new BigDecimal("1200000"));
            assertThat(payment.get().getStatus()).isIn(PaymentStatus.SUCCESS, PaymentStatus.FAILED);
        });
    }

    @Test
    @DisplayName("여러 주문 이벤트를 순차 처리하여 각각 별도의 결제 레코드를 생성한다")
    void processesMultipleOrdersIndependently() {
        for (long i = 1; i <= 3; i++) {
            OrderCreatedEvent event = new OrderCreatedEvent();
            event.setOrderId(i);
            event.setProductName("상품" + i);
            event.setQuantity(1);
            event.setTotalAmount(new BigDecimal(10000 * i));
            kafkaTemplate.send("order-events", String.valueOf(i), event);
        }

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(paymentRepository.findByOrderId(1L)).isPresent();
            assertThat(paymentRepository.findByOrderId(2L)).isPresent();
            assertThat(paymentRepository.findByOrderId(3L)).isPresent();
        });

        assertThat(paymentRepository.findByOrderId(1L).get().getAmount())
                .isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(paymentRepository.findByOrderId(3L).get().getAmount())
                .isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    @DisplayName("결제 조회 API가 저장된 결제 정보를 정확히 반환한다")
    void getPaymentByOrderIdReturnsCorrectData() {
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId(200L);
        event.setProductName("맥북 에어");
        event.setQuantity(1);
        event.setTotalAmount(new BigDecimal("1800000"));

        kafkaTemplate.send("order-events", "200", event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(paymentRepository.findByOrderId(200L)).isPresent();
        });

        PaymentService paymentService = new PaymentService(paymentRepository, kafkaTemplate);
        Payment payment = paymentService.getPaymentByOrderId(200L);
        assertThat(payment.getOrderId()).isEqualTo(200L);
        assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("1800000"));
    }
}
