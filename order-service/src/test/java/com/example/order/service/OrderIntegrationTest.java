package com.example.order.service;

import com.example.order.dto.OrderRequest;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.event.PaymentEvent;
import com.example.order.repository.OrderRepository;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@ActiveProfiles("integration")
class OrderIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("order_db")
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
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("주문 생성 시 DB에 PENDING 상태로 저장된다")
    void createOrderSavesToDatabase() {
        OrderRequest request = new OrderRequest();
        request.setProductName("맥북 프로");
        request.setQuantity(2);
        request.setPrice(new BigDecimal("2500000"));

        Order order = orderService.createOrder(request);

        assertThat(order.getId()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getProductName()).isEqualTo("맥북 프로");
        assertThat(order.getQuantity()).isEqualTo(2);

        Order fromDb = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(fromDb.getProductName()).isEqualTo("맥북 프로");
        assertThat(fromDb.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("여러 주문을 생성하고 전체 목록을 조회할 수 있다")
    void createMultipleOrdersAndListAll() {
        for (int i = 1; i <= 3; i++) {
            OrderRequest request = new OrderRequest();
            request.setProductName("상품" + i);
            request.setQuantity(i);
            request.setPrice(new BigDecimal(10000 * i));
            orderService.createOrder(request);
        }

        List<Order> orders = orderService.getAllOrders();
        assertThat(orders).hasSize(3);
        assertThat(orders).allMatch(o -> o.getStatus() == OrderStatus.PENDING);
    }

    @Test
    @DisplayName("PaymentEvent(SUCCESS) 수신 시 주문 상태가 CONFIRMED로 업데이트된다")
    void paymentSuccessUpdatesOrderToConfirmed() {
        OrderRequest request = new OrderRequest();
        request.setProductName("아이패드");
        request.setQuantity(1);
        request.setPrice(new BigDecimal("1000000"));
        Order order = orderService.createOrder(request);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

        PaymentEvent paymentEvent = new PaymentEvent();
        paymentEvent.setPaymentId(1L);
        paymentEvent.setOrderId(order.getId());
        paymentEvent.setAmount(new BigDecimal("1000000"));
        paymentEvent.setStatus("SUCCESS");
        kafkaTemplate.send("payment-events", String.valueOf(order.getId()), paymentEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Order updated = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        });
    }

    @Test
    @DisplayName("PaymentEvent(FAILED) 수신 시 주문 상태가 CANCELLED로 업데이트된다")
    void paymentFailureUpdatesOrderToCancelled() {
        OrderRequest request = new OrderRequest();
        request.setProductName("에어팟");
        request.setQuantity(1);
        request.setPrice(new BigDecimal("300000"));
        Order order = orderService.createOrder(request);

        PaymentEvent paymentEvent = new PaymentEvent();
        paymentEvent.setPaymentId(2L);
        paymentEvent.setOrderId(order.getId());
        paymentEvent.setAmount(new BigDecimal("300000"));
        paymentEvent.setStatus("FAILED");
        kafkaTemplate.send("payment-events", String.valueOf(order.getId()), paymentEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Order updated = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        });
    }
}
