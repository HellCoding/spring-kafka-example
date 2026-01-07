# 08. Kafka 이벤트 설계

## 토픽 설계

### 토픽 명명 규칙

이 프로젝트에서는 `{도메인}-events` 형식을 사용한다:

| 토픽 | 도메인 | 발행자 | 구독자 |
|------|--------|--------|--------|
| `order-events` | 주문 | Order Service | Payment Service |
| `payment-events` | 결제 | Payment Service | Order Service, Notification Service |
| `notification-events` | 알림 | Notification Service | (없음 — 감사 로그용) |

### 토픽 설계 원칙

**1. 도메인 단위로 토픽을 분리한다**

```
# 좋은 예: 도메인별 분리
order-events
payment-events

# 나쁜 예: 이벤트 타입별 분리
order-created
order-confirmed
order-cancelled
payment-success
payment-failed
```

도메인 단위로 분리하면 관련 이벤트를 하나의 토픽에서 관리할 수 있고, Consumer가 구독할 토픽 수가 줄어든다.

**2. Producer가 토픽을 소유한다**

- `order-events`의 소유자는 Order Service
- `payment-events`의 소유자는 Payment Service
- 다른 서비스가 해당 토픽에 메시지를 넣지 않는다

---

## 이벤트 클래스 설계

### OrderCreatedEvent

```java
// order-service의 이벤트
public class OrderCreatedEvent {
    private Long orderId;
    private String productName;
    private Integer quantity;
    private BigDecimal totalAmount;
}
```

```java
// payment-service의 동일 이벤트 (패키지만 다름)
public class OrderCreatedEvent {
    private Long orderId;
    private String productName;
    private Integer quantity;
    private BigDecimal totalAmount;
}
```

### PaymentEvent

```java
// payment-service의 이벤트
public class PaymentEvent {
    private Long paymentId;
    private Long orderId;
    private BigDecimal amount;
    private String status;  // "SUCCESS" or "FAILED"
}
```

```java
// order-service의 동일 이벤트
// notification-service의 동일 이벤트
// (각각 자기 패키지에 같은 구조의 클래스를 가짐)
```

### 왜 이벤트 클래스를 공유하지 않는가?

마이크로서비스의 **독립 배포** 원칙:
- 공유 라이브러리를 만들면 한 서비스의 이벤트 변경이 다른 서비스에 영향
- 배포할 때 모든 서비스를 동시에 업데이트해야 함
- 이러면 모놀리스와 다를 바 없음

각 서비스가 자기 버전의 이벤트 클래스를 가지면:
- 필요한 필드만 포함할 수 있음
- 독립적으로 배포 가능
- 새 필드가 추가되어도 기존 Consumer는 영향 없음 (JSON의 유연성)

---

## Type Mapping — 핵심 해결 과제

### 문제

Spring Kafka의 `JsonSerializer`는 메시지를 보낼 때 `__TypeId__` 헤더에 **Java 클래스의 FQCN**을 포함한다:

```
Producer (Order Service)가 보낸 메시지:
  Header: __TypeId__ = "com.example.order.event.OrderCreatedEvent"
  Value: {"orderId":1, "productName":"맥북", ...}
```

Consumer (Payment Service)가 이 메시지를 받으면, `com.example.order.event.OrderCreatedEvent` 클래스를 찾으려고 한다. 하지만 Payment Service에는 이 클래스가 없다! Payment Service의 클래스는 `com.example.payment.event.OrderCreatedEvent`이다.

```
결과: ClassNotFoundException → 역직렬화 실패!
```

### 해결: spring.json.type.mapping

논리적인 타입명(alias)을 사용하여 Producer와 Consumer 간 클래스 매핑을 해결한다:

**Producer 측 (Order Service)**:
```yaml
spring.json.type.mapping: orderCreated:com.example.order.event.OrderCreatedEvent
```
→ `__TypeId__` 헤더에 FQCN 대신 `orderCreated`라는 alias가 들어감

**Consumer 측 (Payment Service)**:
```yaml
spring.json.type.mapping: orderCreated:com.example.payment.event.OrderCreatedEvent
```
→ `orderCreated`라는 alias를 받으면 `com.example.payment.event.OrderCreatedEvent`로 역직렬화

### 전체 Type Mapping 설정

```
Order Service (Producer → order-events)
  orderCreated → com.example.order.event.OrderCreatedEvent

Payment Service (Consumer ← order-events)
  orderCreated → com.example.payment.event.OrderCreatedEvent

Payment Service (Producer → payment-events)
  paymentEvent → com.example.payment.event.PaymentEvent

Order Service (Consumer ← payment-events)
  paymentEvent → com.example.order.event.PaymentEvent

Notification Service (Consumer ← payment-events)
  paymentEvent → com.example.notification.event.PaymentEvent

Notification Service (Producer → notification-events)
  notificationSent → com.example.notification.event.NotificationSentEvent
```

### 다이어그램

```
order-events 토픽:
  Producer: Order Service
    alias "orderCreated" ←→ com.example.order.event.OrderCreatedEvent
  Consumer: Payment Service
    alias "orderCreated" ←→ com.example.payment.event.OrderCreatedEvent

payment-events 토픽:
  Producer: Payment Service
    alias "paymentEvent" ←→ com.example.payment.event.PaymentEvent
  Consumer: Order Service
    alias "paymentEvent" ←→ com.example.order.event.PaymentEvent
  Consumer: Notification Service
    alias "paymentEvent" ←→ com.example.notification.event.PaymentEvent
```

---

## Consumer Group 전략

### 이 프로젝트의 Consumer Group 구성

| Consumer Group | 구독 토픽 | 서비스 | 처리 내용 |
|----------------|-----------|--------|-----------|
| `payment-service-group` | `order-events` | Payment Service | 결제 처리 |
| `order-service-group` | `payment-events` | Order Service | 주문 상태 업데이트 |
| `notification-service-group` | `payment-events` | Notification Service | 알림 발송 |

### Consumer Group ID 명명 규칙

`{서비스이름}-group` 형식을 사용한다. 이렇게 하면:
- 어떤 서비스의 Consumer인지 바로 알 수 있다
- Kafka UI에서 Consumer Group 목록을 볼 때 식별이 쉽다
- Consumer lag (밀린 메시지 수)를 서비스별로 모니터링할 수 있다

### payment-events의 팬아웃 패턴

```
Payment Service
      │
      ▼
  payment-events
      │
      ├──▶ order-service-group (Order Service)
      │    → 주문 상태: CONFIRMED / CANCELLED
      │
      └──▶ notification-service-group (Notification Service)
           → 알림: "결제 완료" / "결제 실패"
```

하나의 이벤트가 여러 Consumer Group으로 **팬아웃(fan-out)**되는 패턴. 이것이 이벤트 기반 아키텍처의 강력한 점이다:
- Payment Service는 누가 자기 이벤트를 구독하는지 **알 필요가 없다**
- 새로운 서비스(예: 통계 서비스)를 추가하려면 새 Consumer Group으로 구독만 하면 됨
- 기존 서비스 코드 변경 없음

---

## Kafka UI에서 확인하기

http://localhost:9090 에서 다음을 확인할 수 있다:

1. **Topics** — 생성된 토픽 목록과 각 토픽의 메시지 수
2. **Messages** — 토픽을 클릭하면 실제 메시지 내용 (JSON)을 볼 수 있음
3. **Consumers** — Consumer Group별 현재 offset과 lag 확인
4. **Headers** — 메시지의 `__TypeId__` 헤더에서 type mapping alias 확인 가능

---

[← 이전: 07. Notification Service 상세](07-notification-service.md) | [다음: 09. Docker 환경 구성 →](09-docker-setup.md)
