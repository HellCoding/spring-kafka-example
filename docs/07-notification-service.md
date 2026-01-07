# 07. Notification Service 상세

## 역할

Notification Service는 시스템의 **끝단**이다:
1. `payment-events` 토픽에서 결제 결과를 수신한다
2. 결제 성공/실패에 따른 알림 메시지를 로그로 출력한다
3. 알림 발송 이벤트를 `notification-events` 토픽에 기록한다

실제 서비스에서는 이메일, SMS, 푸시 알림 등으로 대체된다. 이 프로젝트에서는 **로그 출력으로 시뮬레이션**한다.

---

## 프로젝트 구조

```
notification-service/src/main/java/com/example/notification/
├── NotificationServiceApplication.java
├── event/
│   ├── PaymentEvent.java              # Kafka 수신 이벤트
│   └── NotificationSentEvent.java     # Kafka 발행 이벤트
└── service/
    └── NotificationConsumer.java       # Kafka Consumer + 알림 로직
```

### 다른 서비스와의 차이점

- **DB가 없다**: 알림은 발송하고 끝. 별도 저장이 필요 없다 (발송 기록은 Kafka 토픽에 남긴다)
- **REST API가 없다**: 외부에서 직접 호출할 일이 없다. 오직 Kafka 이벤트에 의해서만 동작한다
- **의존성이 가볍다**: JPA, PostgreSQL 드라이버가 필요 없다

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.cloud:spring-cloud-starter-netflix-eureka-client'
    implementation 'org.springframework.kafka:spring-kafka'
    // JPA, PostgreSQL 없음!
}
```

---

## 핵심: NotificationConsumer

```java
@Service
public class NotificationConsumer {

    private static final String NOTIFICATION_EVENTS_TOPIC = "notification-events";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "payment-events", groupId = "notification-service-group")
    public void handlePaymentEvent(PaymentEvent event) {
        log.info("==============================================");
        log.info("NOTIFICATION SERVICE - Received PaymentEvent");
        log.info("Order ID: {}", event.getOrderId());
        log.info("Payment ID: {}", event.getPaymentId());
        log.info("Amount: {}", event.getAmount());
        log.info("Status: {}", event.getStatus());

        String message;
        if ("SUCCESS".equals(event.getStatus())) {
            message = String.format(
                "[SUCCESS] 주문 #%d 결제가 완료되었습니다. 결제 금액: %s원",
                event.getOrderId(), event.getAmount());
            log.info("SENDING NOTIFICATION: {}", message);
        } else {
            message = String.format(
                "[FAILED] 주문 #%d 결제가 실패하였습니다. 다시 시도해주세요.",
                event.getOrderId());
            log.warn("SENDING NOTIFICATION: {}", message);
        }
        log.info("==============================================");

        // 알림 발송 기록을 Kafka에 남김
        NotificationSentEvent notificationEvent = new NotificationSentEvent(
                event.getOrderId(), message, "LOG"
        );
        kafkaTemplate.send(NOTIFICATION_EVENTS_TOPIC,
                String.valueOf(event.getOrderId()), notificationEvent);
    }
}
```

### 로그 출력 예시

결제 성공 시:
```
==============================================
NOTIFICATION SERVICE - Received PaymentEvent
Order ID: 1
Payment ID: 1
Amount: 2500000
Status: SUCCESS
SENDING NOTIFICATION: [SUCCESS] 주문 #1 결제가 완료되었습니다. 결제 금액: 2500000원
==============================================
```

결제 실패 시:
```
==============================================
NOTIFICATION SERVICE - Received PaymentEvent
Order ID: 2
Payment ID: 2
Amount: 1200000
Status: FAILED
SENDING NOTIFICATION: [FAILED] 주문 #2 결제가 실패하였습니다. 다시 시도해주세요.
==============================================
```

### 로그 확인 방법

```bash
# 실시간 로그 추적
docker compose logs -f notification-service

# 알림 메시지만 필터링
docker compose logs notification-service | grep "SENDING NOTIFICATION"
```

---

## Consumer Group: notification-service-group

Order Service와 Notification Service는 **같은 토픽(`payment-events`)을 구독**하지만 **다른 Consumer Group**을 사용한다:

```
Topic: payment-events

Consumer Group: "order-service-group"
└── Order Service  → 주문 상태 업데이트 (CONFIRMED/CANCELLED)

Consumer Group: "notification-service-group"
└── Notification Service  → 알림 발송
```

같은 메시지를 두 서비스가 **독립적으로** 받는다. 이것이 Kafka pub/sub 모델의 핵심이다.

만약 같은 Consumer Group을 쓰면?
- 메시지가 둘 중 하나에만 전달됨
- Order Service가 알림을 받거나, Notification Service가 주문 상태를 변경하는 엉뚱한 상황 발생

---

## notification-events 토픽의 의미

현재 `notification-events` 토픽을 구독하는 Consumer는 없다. 그런데 왜 발행하는가?

1. **감사 로그 (Audit Trail)**: 알림이 언제, 어떤 내용으로 발송되었는지 기록
2. **확장 가능성**: 나중에 알림 발송 현황을 모니터링하는 대시보드 서비스를 붙일 수 있음
3. **Kafka의 메시지 보존**: Consumer가 없어도 메시지는 Kafka에 저장됨. 나중에 Consumer를 추가하면 과거 메시지도 읽을 수 있음

---

## 실제 서비스로 확장한다면?

```java
// 현재 (시뮬레이션)
log.info("SENDING NOTIFICATION: {}", message);

// 실제 구현 예시
@Service
public class NotificationConsumer {

    private final EmailService emailService;
    private final SmsService smsService;
    private final PushService pushService;

    @KafkaListener(topics = "payment-events", groupId = "notification-service-group")
    public void handlePaymentEvent(PaymentEvent event) {
        // 사용자 정보 조회 (User Service API 호출)
        User user = userClient.getUser(event.getUserId());

        // 채널별 알림 발송
        emailService.send(user.getEmail(), buildEmailContent(event));
        smsService.send(user.getPhone(), buildSmsContent(event));
        pushService.send(user.getDeviceToken(), buildPushContent(event));
    }
}
```

---

[← 이전: 06. Payment Service 상세](06-payment-service.md) | [다음: 08. Kafka 이벤트 설계 →](08-kafka-event-flow.md)
