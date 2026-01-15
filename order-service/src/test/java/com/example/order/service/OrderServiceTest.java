package com.example.order.service;

import com.example.order.dto.OrderRequest;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.event.OrderCreatedEvent;
import com.example.order.exception.OrderNotFoundException;
import com.example.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private OrderService orderService;

    @Nested
    @DisplayName("createOrder")
    class CreateOrder {

        private OrderRequest request;

        @BeforeEach
        void setUp() {
            request = new OrderRequest();
            request.setProductName("맥북 프로");
            request.setQuantity(2);
            request.setPrice(new BigDecimal("2500000"));
        }

        @Test
        @DisplayName("주문을 PENDING 상태로 저장하고 Kafka 이벤트를 발행한다")
        void savesOrderAndPublishesEvent() {
            Order savedOrder = new Order();
            savedOrder.setId(1L);
            savedOrder.setProductName("맥북 프로");
            savedOrder.setQuantity(2);
            savedOrder.setPrice(new BigDecimal("2500000"));
            savedOrder.setStatus(OrderStatus.PENDING);

            when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

            Order result = orderService.createOrder(request);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(result.getProductName()).isEqualTo("맥북 프로");

            verify(orderRepository).save(any(Order.class));
        }

        @Test
        @DisplayName("Kafka에 올바른 토픽과 키로 OrderCreatedEvent를 발행한다")
        void publishesCorrectKafkaEvent() {
            Order savedOrder = new Order();
            savedOrder.setId(1L);
            savedOrder.setProductName("맥북 프로");
            savedOrder.setQuantity(2);
            savedOrder.setPrice(new BigDecimal("2500000"));
            savedOrder.setStatus(OrderStatus.PENDING);

            when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

            orderService.createOrder(request);

            ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
            verify(kafkaTemplate).send(eq("order-events"), eq("1"), eventCaptor.capture());

            OrderCreatedEvent event = eventCaptor.getValue();
            assertThat(event.getOrderId()).isEqualTo(1L);
            assertThat(event.getProductName()).isEqualTo("맥북 프로");
            assertThat(event.getQuantity()).isEqualTo(2);
            assertThat(event.getTotalAmount()).isEqualByComparingTo(new BigDecimal("5000000"));
        }
    }

    @Nested
    @DisplayName("getOrder")
    class GetOrder {

        @Test
        @DisplayName("존재하는 주문을 반환한다")
        void returnsExistingOrder() {
            Order order = new Order();
            order.setId(1L);
            order.setProductName("아이패드");
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

            Order result = orderService.getOrder(1L);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getProductName()).isEqualTo("아이패드");
        }

        @Test
        @DisplayName("존재하지 않는 주문 조회 시 OrderNotFoundException을 던진다")
        void throwsWhenOrderNotFound() {
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.getOrder(999L))
                    .isInstanceOf(OrderNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getAllOrders")
    class GetAllOrders {

        @Test
        @DisplayName("모든 주문 목록을 반환한다")
        void returnsAllOrders() {
            Order order1 = new Order();
            order1.setId(1L);
            Order order2 = new Order();
            order2.setId(2L);
            when(orderRepository.findAll()).thenReturn(List.of(order1, order2));

            List<Order> result = orderService.getAllOrders();

            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("updateOrderStatus")
    class UpdateOrderStatus {

        @Test
        @DisplayName("결제 성공 시 주문 상태를 CONFIRMED로 업데이트한다")
        void updatesStatusToConfirmed() {
            Order order = new Order();
            order.setId(1L);
            order.setStatus(OrderStatus.PENDING);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenReturn(order);

            orderService.updateOrderStatus(1L, OrderStatus.CONFIRMED);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            verify(orderRepository).save(order);
        }

        @Test
        @DisplayName("결제 실패 시 주문 상태를 CANCELLED로 업데이트한다")
        void updatesStatusToCancelled() {
            Order order = new Order();
            order.setId(1L);
            order.setStatus(OrderStatus.PENDING);
            when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenReturn(order);

            orderService.updateOrderStatus(1L, OrderStatus.CANCELLED);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            verify(orderRepository).save(order);
        }
    }
}
