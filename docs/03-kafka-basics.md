# 03. Apache Kafka 핵심 개념

## Kafka란?

Apache Kafka는 **분산 이벤트 스트리밍 플랫폼**이다. 간단히 말하면, 서비스 간에 메시지를 주고받는 **우체국** 같은 역할을 한다.

기존 메시지 큐(RabbitMQ 등)와 다른 점:
- 메시지를 **디스크에 저장**한다 (날아가지 않음)
- Consumer가 메시지를 읽어도 **삭제하지 않는다** (다른 Consumer도 읽을 수 있음)
- **순서가 보장**된다 (같은 Partition 내에서)
- 엄청난 **처리량**을 자랑한다 (초당 수백만 메시지)

---

## 핵심 개념 5가지

### 1. Topic (토픽)

메시지가 저장되는 **논리적 채널**. 우체국의 "우편함"에 해당한다.

```
Topic: order-events      ← 주문 관련 이벤트가 여기에 저장
Topic: payment-events    ← 결제 관련 이벤트가 여기에 저장
Topic: notification-events ← 알림 관련 이벤트가 여기에 저장
```

- 토픽은 이름으로 구분한다
- Producer가 특정 토픽에 메시지를 발행하면, 해당 토픽을 구독하는 Consumer가 메시지를 받는다

### 2. Producer (프로듀서)

메시지를 **발행하는 쪽**. 우체국에 편지를 넣는 사람.

```java
// Order Service가 Producer 역할
kafkaTemplate.send("order-events", orderId, orderCreatedEvent);
//                   ↑ 토픽         ↑ 키     ↑ 메시지 값
```

- `토픽`: 어느 우편함에 넣을지
- `키`: 같은 키의 메시지는 같은 Partition에 들어간다 (순서 보장용)
- `값`: 실제 전달할 데이터 (JSON 직렬화)

### 3. Consumer (컨슈머)

메시지를 **구독하는 쪽**. 우편함에서 편지를 꺼내 읽는 사람.

```java
// Payment Service가 Consumer 역할
@KafkaListener(topics = "order-events", groupId = "payment-service-group")
public void handleOrderCreated(OrderCreatedEvent event) {
    // 메시지 처리 로직
}
```

### 4. Partition (파티션)

하나의 토픽을 **여러 조각으로 분할**한 것. 병렬 처리의 핵심.

```
Topic: order-events
├── Partition 0: [msg1] [msg4] [msg7]
├── Partition 1: [msg2] [msg5] [msg8]
└── Partition 2: [msg3] [msg6] [msg9]
```

- 같은 Partition 내에서는 **순서가 보장**된다
- 다른 Partition 간에는 순서가 보장되지 않는다
- Producer가 메시지의 `key`를 기반으로 어느 Partition에 넣을지 결정한다
  - 예: orderId=1의 모든 이벤트는 항상 같은 Partition에 들어감

> 이 프로젝트에서는 학습 목적으로 Partition을 1개만 사용한다 (기본값).

### 5. Consumer Group (컨슈머 그룹)

**가장 중요한 개념**. 같은 토픽을 여러 서비스가 구독할 때의 동작을 결정한다.

```
Topic: payment-events

Consumer Group: "order-service-group"
└── Order Service  ← 이 그룹에서 메시지를 하나씩 가져감

Consumer Group: "notification-service-group"
└── Notification Service  ← 이 그룹에서도 동일한 메시지를 가져감
```

**규칙**:
- **같은 Consumer Group** 내의 Consumer들은 메시지를 **나누어** 가져간다 (병렬 처리)
- **다른 Consumer Group**은 **모든 메시지를 독립적으로** 가져간다 (pub/sub)

이 프로젝트에서:
- `payment-events` 토픽의 메시지를 `order-service-group`과 `notification-service-group`이 **각각** 받는다
- 둘 다 같은 메시지를 받지만, 하는 일은 다르다:
  - Order Service → 주문 상태 업데이트
  - Notification Service → 알림 발송

---

## Offset (오프셋)

Kafka가 Consumer의 **읽기 위치**를 추적하는 방법.

```
Partition 0: [msg0] [msg1] [msg2] [msg3] [msg4] [msg5]
                                    ↑
                              current offset = 3
                         (여기까지 읽었다는 뜻)
```

- Consumer가 메시지를 처리하면 offset을 커밋 (commit)한다
- 서비스가 재시작되면 **마지막 커밋된 offset부터** 다시 읽기 시작
- 메시지 유실이나 중복 처리를 방지하는 핵심 메커니즘

### auto-offset-reset

Consumer가 처음 시작할 때 (커밋된 offset이 없을 때) 어디서부터 읽을지 결정:

```yaml
spring:
  kafka:
    consumer:
      auto-offset-reset: earliest  # 처음부터 (모든 메시지)
      # auto-offset-reset: latest  # 지금부터 (새 메시지만)
```

이 프로젝트에서는 `earliest`를 사용한다. 서비스가 늦게 시작되더라도 밀린 메시지를 모두 처리하도록.

---

## KRaft 모드

전통적으로 Kafka는 클러스터 관리를 위해 **Zookeeper**라는 별도 서비스가 필요했다. KRaft(Kafka Raft) 모드는 Zookeeper 없이 Kafka가 **자체적으로 클러스터를 관리**하는 새로운 방식이다.

```
# 기존 (Kafka + Zookeeper)
docker-compose:
  zookeeper:    ← 필요했음
  kafka:        ← Zookeeper에 의존

# KRaft 모드 (이 프로젝트)
docker-compose:
  kafka:        ← 단독 실행. Zookeeper 불필요
```

설정에서 KRaft 모드를 활성화하는 핵심 환경 변수:

```yaml
environment:
  KAFKA_PROCESS_ROLES: broker,controller    # 브로커 + 컨트롤러 역할 동시 수행
  KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093  # 자기 자신이 투표자
  KAFKA_NODE_ID: 1
```

---

## Kafka vs RabbitMQ

| | Kafka | RabbitMQ |
|---|-------|----------|
| 모델 | 로그 기반 (append-only) | 큐 기반 (consume & delete) |
| 메시지 보존 | 읽어도 삭제 안 함 | Consumer가 읽으면 삭제 |
| 순서 보장 | Partition 내 보장 | 큐 내 보장 |
| 처리량 | 매우 높음 | 보통 |
| 사용 사례 | 이벤트 스트리밍, 로그 | 작업 큐, RPC |

**이 프로젝트에서 Kafka를 선택한 이유**:
- 같은 이벤트를 여러 서비스가 독립적으로 구독해야 하므로, 메시지가 삭제되지 않는 Kafka가 적합
- 이벤트 소싱 패턴과 자연스럽게 연결됨

---

[← 이전: 02. Spring Cloud 개념 정리](02-spring-cloud.md) | [다음: 04. Eureka + Gateway 구현 →](04-eureka-gateway.md)
