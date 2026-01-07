# 01. 아키텍처 설계

## 전체 시스템 구조

```
                    ┌─────────────────────────────────────────────────────────┐
                    │                    Docker Network                       │
                    │                                                         │
 Client ──HTTP──▶  │  ┌──────────────┐                                       │
                    │  │ API Gateway  │                                       │
                    │  │    :8080     │                                       │
                    │  └──────┬───────┘                                       │
                    │         │ Eureka를 통한 서비스 라우팅                      │
                    │         │                                               │
                    │  ┌──────┴──────────────────────────────────┐            │
                    │  │          Eureka Server :8761             │            │
                    │  │        (서비스 레지스트리)                 │            │
                    │  └──────┬──────────────┬──────────────┬────┘            │
                    │         │              │              │                 │
                    │  ┌──────┴─────┐ ┌──────┴─────┐ ┌─────┴──────┐         │
                    │  │   Order    │ │  Payment   │ │Notification│         │
                    │  │  Service   │ │  Service   │ │  Service   │         │
                    │  │   :8081    │ │   :8082    │ │   :8083    │         │
                    │  └──────┬─────┘ └──────┬─────┘ └─────┬──────┘         │
                    │         │              │              │                 │
                    │         └──────────────┼──────────────┘                 │
                    │                        │                               │
                    │                 ┌──────┴──────┐                        │
                    │                 │    Kafka    │                        │
                    │                 │    :9092    │                        │
                    │                 └─────────────┘                        │
                    │                                                         │
                    │  ┌─────────────┐              ┌─────────────┐          │
                    │  │ PostgreSQL  │              │  Kafka UI   │          │
                    │  │    :5432    │              │    :9090    │          │
                    │  └─────────────┘              └─────────────┘          │
                    └─────────────────────────────────────────────────────────┘
```

---

## 서비스 간 통신 방식

이 프로젝트에서는 **두 가지 통신 방식**이 공존한다:

### 1. 동기 통신 (HTTP)

```
Client → API Gateway → Order Service (REST API)
Client → API Gateway → Payment Service (REST API)
```

- 클라이언트가 주문을 생성하거나 조회할 때 사용
- API Gateway가 Eureka에서 서비스 위치를 찾아 라우팅
- 요청-응답 패턴, 즉시 결과를 받음

### 2. 비동기 통신 (Kafka)

```
Order Service ──▶ Kafka ──▶ Payment Service
Payment Service ──▶ Kafka ──▶ Order Service
Payment Service ──▶ Kafka ──▶ Notification Service
```

- 서비스 간 이벤트 전달에 사용
- 발행자는 Kafka에 메시지를 넣고 즉시 반환 (fire-and-forget)
- 구독자는 자기 속도로 메시지를 처리

### 왜 두 가지를 함께 쓰는가?

| 상황 | 적합한 방식 | 이유 |
|------|-------------|------|
| 주문 생성 요청 | 동기 (HTTP) | 클라이언트가 즉시 주문 ID를 받아야 함 |
| 주문 → 결제 처리 | 비동기 (Kafka) | 결제에 시간이 걸릴 수 있고, Order Service가 기다릴 필요 없음 |
| 결제 → 주문 상태 변경 | 비동기 (Kafka) | Payment가 Order를 직접 호출하면 강한 결합이 생김 |
| 결제 → 알림 발송 | 비동기 (Kafka) | 알림 실패가 결제에 영향을 주면 안 됨 |

---

## 데이터 흐름 (시간순)

```
시간 →

t0: Client ──POST /api/orders──▶ Gateway ──▶ Order Service
    Order Service: DB에 주문 저장 (PENDING)
    Order Service: 응답 반환 {id:1, status:PENDING}

t1: Order Service ──OrderCreatedEvent──▶ Kafka [order-events]

t2: Payment Service ◀── Kafka [order-events]
    Payment Service: 결제 처리 (70% 성공)
    Payment Service: DB에 결제 결과 저장

t3: Payment Service ──PaymentEvent──▶ Kafka [payment-events]

t4: Order Service ◀── Kafka [payment-events]
    Order Service: 주문 상태 업데이트 (CONFIRMED or CANCELLED)

t4: Notification Service ◀── Kafka [payment-events]  (동시)
    Notification Service: 알림 로그 출력
    Notification Service ──NotificationSentEvent──▶ Kafka [notification-events]

t5: Client ──GET /api/orders/1──▶ Gateway ──▶ Order Service
    응답: {id:1, status:CONFIRMED}
```

**핵심**: t0에서 클라이언트가 받는 응답은 `PENDING`이다. 실제 결제와 상태 변경은 **비동기적으로** 나중에 일어난다. 클라이언트는 나중에 다시 조회하여 최종 상태를 확인한다.

---

## 데이터베이스 설계

마이크로서비스의 **Database per Service** 패턴을 따른다:

```
PostgreSQL (단일 인스턴스)
├── order_db     ← Order Service 전용
│   └── orders 테이블
└── payment_db   ← Payment Service 전용
    └── payments 테이블
```

### 왜 DB를 분리하는가?

- 각 서비스가 자기 데이터에 대한 **완전한 소유권**을 가짐
- 다른 서비스의 DB 스키마 변경에 영향받지 않음
- 서비스별로 독립적인 스케일링 가능

> 이 프로젝트에서는 편의상 하나의 PostgreSQL 인스턴스 안에 별도 데이터베이스로 분리했다.
> 프로덕션에서는 완전히 별도의 DB 인스턴스를 사용하는 것이 이상적이다.

### orders 테이블

```sql
CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    product_name VARCHAR(255) NOT NULL,
    quantity    INT NOT NULL,
    price       DECIMAL(10,2) NOT NULL,
    status      VARCHAR(20) NOT NULL,   -- PENDING | CONFIRMED | CANCELLED
    created_at  TIMESTAMP NOT NULL
);
```

### payments 테이블

```sql
CREATE TABLE payments (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    status      VARCHAR(20) NOT NULL,   -- SUCCESS | FAILED
    created_at  TIMESTAMP NOT NULL
);
```

---

## 포트 매핑 정리

| 포트 | 서비스 | 접근 URL |
|------|--------|----------|
| 8080 | API Gateway | http://localhost:8080 |
| 8081 | Order Service | http://localhost:8081 |
| 8082 | Payment Service | http://localhost:8082 |
| 8083 | Notification Service | http://localhost:8083 |
| 8761 | Eureka Server | http://localhost:8761 |
| 9090 | Kafka UI | http://localhost:9090 |
| 9092 | Kafka Broker | (내부 통신용) |
| 5432 | PostgreSQL | (내부 통신용) |

---

[← 이전: 00. 프로젝트 개요](00-overview.md) | [다음: 02. Spring Cloud 개념 정리 →](02-spring-cloud.md)
