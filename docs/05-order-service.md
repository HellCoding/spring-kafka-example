# 05. Order Service 상세

## 역할

Order Service는 이 시스템의 **시작점**이다:
1. 클라이언트로부터 주문 요청을 받는다 (REST API)
2. 주문을 DB에 저장한다 (JPA)
3. 주문 생성 이벤트를 Kafka에 발행한다 (Producer)
4. 결제 결과 이벤트를 Kafka에서 수신하여 주문 상태를 업데이트한다 (Consumer)

---

## 프로젝트 구조

```
order-service/src/main/java/com/example/order/
├── OrderServiceApplication.java    # 메인 클래스
├── controller/
│   └── OrderController.java        # REST API 엔드포인트
├── dto/
│   └── OrderRequest.java           # 요청 DTO
├── entity/
│   ├── Order.java                  # JPA 엔티티
│   └── OrderStatus.java           # 상태 Enum
├── event/
│   ├── OrderCreatedEvent.java     # Kafka 발행 이벤트
│   └── PaymentEvent.java          # Kafka 수신 이벤트
├── repository/
│   └── OrderRepository.java       # Spring Data JPA
└── service/
    ├── OrderService.java           # 비즈니스 로직 + Kafka Producer
    └── PaymentEventConsumer.java   # Kafka Consumer
```

---

## Entity: Order

```java
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = OrderStatus.PENDING;
        }
    }
}
```

### 포인트

- **`@Table(name = "orders")`**: `order`는 SQL 예약어이므로 복수형 `orders` 사용
- **`@Enumerated(EnumType.STRING)`**: Enum을 숫자(0, 1, 2)가 아닌 문자열("PENDING", "CONFIRMED")로 저장. DB를 직접 볼 때 읽기 편함
- **`@PrePersist`**: 엔티티가 저장되기 직전에 자동 호출. 생성 시각과 초기 상태를 설정

### OrderStatus

```java
public enum OrderStatus {
    PENDING,      // 주문 생성됨, 결제 대기 중
    CONFIRMED,    // 결제 성공, 주문 확정
    CANCELLED     // 결제 실패, 주문 취소
}
```

주문의 생명주기:
```
PENDING ──결제 성공──▶ CONFIRMED
PENDING ──결제 실패──▶ CANCELLED
```

---

## Controller: OrderController

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody OrderRequest request) {
        Order order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }

    @GetMapping
    public ResponseEntity<List<Order>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }
}
```

- **`@RequestMapping("/api/orders")`**: API Gateway에서 `/api/orders/**` 패턴으로 라우팅됨
- **`HttpStatus.CREATED`**: POST 성공 시 201 상태 코드 반환 (REST 관례)

---

## Service: OrderService (Kafka Producer)

```java
@Service
public class OrderService {

    private static final String ORDER_EVENTS_TOPIC = "order-events";

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public Order createOrder(OrderRequest request) {
        // 1. 주문 생성 및 DB 저장
        Order order = new Order();
        order.setProductName(request.getProductName());
        order.setQuantity(request.getQuantity());
        order.setPrice(request.getPrice());
        order.setStatus(OrderStatus.PENDING);
        Order savedOrder = orderRepository.save(order);

        // 2. Kafka 이벤트 발행
        OrderCreatedEvent event = new OrderCreatedEvent(
                savedOrder.getId(),
                savedOrder.getProductName(),
                savedOrder.getQuantity(),
                savedOrder.getPrice().multiply(BigDecimal.valueOf(savedOrder.getQuantity()))
        );
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, String.valueOf(savedOrder.getId()), event);

        return savedOrder;
    }
}
```

### KafkaTemplate.send() 분석

```java
kafkaTemplate.send(
    ORDER_EVENTS_TOPIC,                    // 토픽 이름
    String.valueOf(savedOrder.getId()),     // 메시지 키 (Partition 결정용)
    event                                  // 메시지 값 (JSON 직렬화됨)
);
```

- **키를 orderId로 설정하는 이유**: 같은 주문의 모든 이벤트가 같은 Partition에 들어가도록. 이렇게 하면 해당 주문에 대한 이벤트 순서가 보장됨
- **`KafkaTemplate<String, Object>`**: Key는 String, Value는 Object(JsonSerializer가 JSON으로 변환)

### @Transactional의 범위

`@Transactional` 안에서 DB 저장과 Kafka 발행을 함께 수행한다. 엄밀하게는 이 둘의 트랜잭션이 완전히 동기화되지 않는다 (DB 커밋은 되었는데 Kafka 발행이 실패할 수 있음). 프로덕션에서는 **Outbox 패턴** 등으로 해결해야 하지만, 학습 프로젝트이므로 단순하게 구현했다.

---

## Consumer: PaymentEventConsumer

```java
@Component
public class PaymentEventConsumer {

    private final OrderService orderService;

    @KafkaListener(topics = "payment-events", groupId = "order-service-group")
    public void handlePaymentEvent(PaymentEvent event) {
        if ("SUCCESS".equals(event.getStatus())) {
            orderService.updateOrderStatus(event.getOrderId(), OrderStatus.CONFIRMED);
        } else {
            orderService.updateOrderStatus(event.getOrderId(), OrderStatus.CANCELLED);
        }
    }
}
```

### @KafkaListener 분석

```java
@KafkaListener(
    topics = "payment-events",         // 구독할 토픽
    groupId = "order-service-group"    // Consumer Group ID
)
```

- `topics`: 어떤 토픽을 구독할지
- `groupId`: Consumer Group 이름. Notification Service도 같은 토픽을 구독하지만 **다른 groupId**를 사용하므로, 두 서비스가 같은 메시지를 각각 독립적으로 받는다

### Consumer와 Service 분리

`PaymentEventConsumer`와 `OrderService`를 분리한 이유:
- **단일 책임 원칙**: Consumer는 메시지 수신만, Service는 비즈니스 로직만
- **테스트 용이성**: Consumer 없이 OrderService만 테스트할 수 있음
- **순환 참조 방지**: OrderService에 @KafkaListener를 넣으면, KafkaTemplate 주입과 @KafkaListener가 같은 빈에 있게 됨

---

## 이벤트 클래스

### OrderCreatedEvent (발행)

```java
public class OrderCreatedEvent {
    private Long orderId;
    private String productName;
    private Integer quantity;
    private BigDecimal totalAmount;  // 단가 × 수량
}
```

### PaymentEvent (수신)

```java
public class PaymentEvent {
    private Long paymentId;
    private Long orderId;
    private BigDecimal amount;
    private String status;  // "SUCCESS" or "FAILED"
}
```

> 각 서비스가 독립적인 이벤트 클래스를 가진다. 패키지가 다르므로 Type Mapping이 필요하다 (08-kafka-event-flow.md 참고).

---

## Kafka 설정 (application.yml)

```yaml
spring:
  kafka:
    bootstrap-servers: kafka:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        spring.json.type.mapping: orderCreated:com.example.order.event.OrderCreatedEvent
    consumer:
      group-id: order-service-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"
        spring.json.type.mapping: paymentEvent:com.example.order.event.PaymentEvent
```

| 설정 | 설명 |
|------|------|
| `bootstrap-servers` | Kafka 브로커 주소 |
| `key-serializer` | 키를 String으로 직렬화 |
| `value-serializer` | 값을 JSON으로 직렬화 |
| `spring.json.type.mapping` | 논리적 타입명과 Java 클래스 매핑 |
| `auto-offset-reset` | 첫 구독 시 처음부터 읽기 |
| `spring.json.trusted.packages` | 역직렬화 허용 패키지 (`*` = 모든 패키지) |

---

[← 이전: 04. Eureka + Gateway 구현](04-eureka-gateway.md) | [다음: 06. Payment Service 상세 →](06-payment-service.md)
