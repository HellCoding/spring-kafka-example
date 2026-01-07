# 06. Payment Service 상세

## 역할

Payment Service는 **Kafka Consumer이자 Producer**이다:
1. `order-events` 토픽에서 주문 생성 이벤트를 수신한다 (Consumer)
2. 결제를 처리한다 (시뮬레이션)
3. 결제 결과를 DB에 저장한다 (JPA)
4. 결제 결과 이벤트를 `payment-events` 토픽에 발행한다 (Producer)
5. 주문별 결제 정보를 조회하는 REST API를 제공한다

---

## 프로젝트 구조

```
payment-service/src/main/java/com/example/payment/
├── PaymentServiceApplication.java
├── controller/
│   └── PaymentController.java        # REST API
├── entity/
│   ├── Payment.java                  # JPA 엔티티
│   └── PaymentStatus.java           # SUCCESS / FAILED
├── event/
│   ├── OrderCreatedEvent.java       # Kafka 수신 이벤트
│   └── PaymentEvent.java            # Kafka 발행 이벤트
├── repository/
│   └── PaymentRepository.java
└── service/
    └── PaymentService.java           # Consumer + Producer + 비즈니스 로직
```

---

## 핵심: PaymentService (Consumer + Producer)

```java
@Service
public class PaymentService {

    private static final String PAYMENT_EVENTS_TOPIC = "payment-events";
    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Random random = new Random();

    @KafkaListener(topics = "order-events", groupId = "payment-service-group")
    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent: orderId={}", event.getOrderId());

        // 1. 결제 처리 시뮬레이션 (70% 성공, 30% 실패)
        boolean paymentSuccess = random.nextInt(100) < 70;

        // 2. DB 저장
        Payment payment = new Payment();
        payment.setOrderId(event.getOrderId());
        payment.setAmount(event.getTotalAmount());
        payment.setStatus(paymentSuccess ? PaymentStatus.SUCCESS : PaymentStatus.FAILED);
        Payment savedPayment = paymentRepository.save(payment);

        // 3. 결제 결과 이벤트 발행
        PaymentEvent paymentEvent = new PaymentEvent(
                savedPayment.getId(),
                savedPayment.getOrderId(),
                savedPayment.getAmount(),
                savedPayment.getStatus().name()
        );
        kafkaTemplate.send(PAYMENT_EVENTS_TOPIC,
                String.valueOf(event.getOrderId()), paymentEvent);
    }
}
```

### 코드 흐름

```
Kafka [order-events]
        │
        ▼
@KafkaListener 수신
        │
        ▼
결제 시뮬레이션 (Random: 70% 성공)
        │
        ├── 성공 → Payment(status=SUCCESS) 저장
        └── 실패 → Payment(status=FAILED) 저장
        │
        ▼
PaymentEvent 생성
        │
        ▼
Kafka [payment-events] 발행
        │
        ├──▶ Order Service가 수신 → 주문 상태 변경
        └──▶ Notification Service가 수신 → 알림 발송
```

### 결제 시뮬레이션

```java
boolean paymentSuccess = random.nextInt(100) < 70;
```

0~99 사이의 랜덤 정수를 생성하여 70 미만이면 성공(70%), 이상이면 실패(30%). 실제 서비스에서는 PG사 API 호출 등으로 대체된다.

이 시뮬레이션 덕분에 여러 주문을 생성하면 CONFIRMED와 CANCELLED가 섞여서 나오므로, 이벤트 흐름의 분기를 확인할 수 있다.

---

## Entity: Payment

```java
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;    // SUCCESS or FAILED

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
```

### orderId를 외래키(FK)로 만들지 않은 이유

마이크로서비스 패턴에서 각 서비스는 **자기 DB만 접근**한다. `order_db`와 `payment_db`는 서로 다른 데이터베이스이므로 DB 레벨의 외래키 제약을 걸 수 없다.

대신 `orderId`는 논리적 참조로만 사용되며, 데이터 정합성은 Kafka 이벤트를 통해 **이벤트 기반으로** 보장한다.

---

## Repository: PaymentRepository

```java
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
}
```

`findByOrderId`는 Spring Data JPA의 **쿼리 메서드 네이밍 규칙**에 의해 자동으로 SQL이 생성된다:

```sql
SELECT * FROM payments WHERE order_id = ?
```

---

## Controller: PaymentController

```java
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    @GetMapping("/order/{orderId}")
    public ResponseEntity<Payment> getPaymentByOrderId(@PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.getPaymentByOrderId(orderId));
    }
}
```

주문에 대한 결제 정보를 조회하는 단일 엔드포인트. API Gateway에서 `/api/payments/**`로 라우팅된다.

---

## Consumer Group 동작

Payment Service의 Consumer Group ID는 `payment-service-group`이다:

```
Topic: order-events
├── Consumer Group: payment-service-group
│   └── Payment Service instance 1  ← 여기서 메시지를 받음
│
│  (만약 payment-service를 2개 띄우면?)
├── Consumer Group: payment-service-group
│   ├── Payment Service instance 1  ← Partition 0 담당
│   └── Payment Service instance 2  ← Partition 1 담당
```

같은 Consumer Group 내의 인스턴스들은 메시지를 **나누어** 처리한다. 이것이 Kafka의 수평 확장 메커니즘이다.

---

[← 이전: 05. Order Service 상세](05-order-service.md) | [다음: 07. Notification Service 상세 →](07-notification-service.md)
