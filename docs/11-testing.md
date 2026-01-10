# 11. 테스트 시나리오 가이드

이 문서는 프로젝트의 전체 이벤트 흐름을 단계별로 검증하는 테스트 시나리오를 다룬다.

---

## 테스트 방법 선택

| 방법 | URL | 특징 |
|------|-----|------|
| **Swagger UI (Gateway 통합)** | http://localhost:8080/webjars/swagger-ui/index.html | 브라우저에서 API 테스트, 서비스별 드롭다운 선택 |
| **Swagger UI (Order Service 직접)** | http://localhost:8081/swagger-ui/index.html | Order Service API만 테스트 |
| **Swagger UI (Payment Service 직접)** | http://localhost:8082/swagger-ui/index.html | Payment Service API만 테스트 |
| **Kafka UI** | http://localhost:9090 | 토픽, 메시지, 컨슈머 그룹 모니터링 |
| **Eureka Dashboard** | http://localhost:8761 | 서비스 등록 상태 확인 |
| **curl** | 터미널 | CLI 기반 빠른 테스트 |

---

## 사전 준비

```bash
# 1. 전체 서비스 기동
cd spring-kafka-example
docker compose up -d --build

# 2. 컨테이너 상태 확인 (모두 running/healthy)
docker compose ps

# 3. Eureka 등록 대기 (약 30초)
#    확인: http://localhost:8761 에서 ORDER-SERVICE, PAYMENT-SERVICE 등록 확인
```

> Eureka 등록 전에 Gateway(`:8080`)로 요청하면 503이 반환된다. 직접 서비스 포트(`:8081`, `:8082`)로 테스트하면 즉시 사용 가능.

---

## 시나리오 1: 기본 주문 → 결제 → 알림 흐름

전체 이벤트 파이프라인을 검증하는 핵심 테스트.

### Step 1. 주문 생성

**Swagger UI:**
1. http://localhost:8080/webjars/swagger-ui/index.html 접속
2. 드롭다운에서 **Order Service** 선택
3. `POST /api/orders` → **Try it out** 클릭
4. Request body 입력:
```json
{
  "productName": "맥북 프로",
  "quantity": 1,
  "price": 2500000
}
```
5. **Execute** 클릭

**curl:**
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productName":"맥북 프로","quantity":1,"price":2500000}'
```

**기대 결과:**
```json
{
  "id": 1,
  "productName": "맥북 프로",
  "quantity": 1,
  "price": 2500000,
  "status": "PENDING",
  "createdAt": "2024-..."
}
```

- `status`가 `PENDING`인 것을 확인 — 아직 결제 처리 전

### Step 2. 주문 상태 확인 (2~3초 후)

**Swagger UI:** `GET /api/orders/{id}` → id에 `1` 입력 → Execute

**curl:**
```bash
curl http://localhost:8080/api/orders/1
```

**기대 결과:**
- `status`가 `CONFIRMED` (결제 성공, 70% 확률) 또는 `CANCELLED` (결제 실패, 30% 확률)로 변경됨

### Step 3. 결제 정보 확인

**Swagger UI:** 드롭다운에서 **Payment Service** 선택 → `GET /api/payments/order/{orderId}` → orderId에 `1` → Execute

**curl:**
```bash
curl http://localhost:8080/api/payments/order/1
```

**기대 결과:**
```json
{
  "id": 1,
  "orderId": 1,
  "amount": 2500000,
  "status": "SUCCESS",
  "createdAt": "2024-..."
}
```

### Step 4. 로그로 이벤트 흐름 추적

```bash
docker compose logs -f order-service payment-service notification-service
```

**기대 로그 순서:**
```
order-service        | Order created: id=1, product=맥북 프로
order-service        | Published OrderCreatedEvent to Kafka: orderId=1
payment-service      | Received OrderCreatedEvent: orderId=1, amount=2500000
payment-service      | Payment processed: id=1, orderId=1, status=SUCCESS
payment-service      | Published PaymentEvent to Kafka: orderId=1, status=SUCCESS
order-service        | Order status updated: id=1, status=CONFIRMED
notification-service | NOTIFICATION SERVICE - Received PaymentEvent
notification-service | SENDING NOTIFICATION: [SUCCESS] 주문 #1 결제가 완료되었습니다. 결제 금액: 2500000원
notification-service | Published NotificationSentEvent to Kafka: orderId=1
```

---

## 시나리오 2: 다건 주문으로 성공/실패 비율 확인

결제 시뮬레이션의 70% 성공 / 30% 실패 비율을 검증한다.

```bash
# 10건 동시 주문
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/api/orders \
    -H "Content-Type: application/json" \
    -d "{\"productName\":\"상품$i\",\"quantity\":1,\"price\":10000}" &
done
wait

# 3초 후 전체 주문 조회
sleep 3
curl -s http://localhost:8080/api/orders | python3 -m json.tool
```

**확인 포인트:**
- 전체 주문이 `CONFIRMED` 또는 `CANCELLED` 상태로 업데이트됨
- 대략 7:3 비율로 성공/실패가 분포 (랜덤이므로 매번 다름)
- 모든 주문이 `PENDING` 상태로 남아있지 않음 (이벤트 처리 완료)

---

## 시나리오 3: Kafka UI로 메시지 흐름 확인

1. http://localhost:9090 접속
2. 좌측 메뉴에서 **Topics** 클릭

### 토픽별 확인

| 토픽 | 확인 사항 |
|------|----------|
| `order-events` | 주문 생성 시 메시지 발행 확인. `__TypeId__` 헤더에 `orderCreated` (FQCN이 아닌 alias) |
| `payment-events` | 결제 처리 후 메시지 발행 확인. status가 `SUCCESS` 또는 `FAILED` |
| `notification-events` | 알림 발송 후 메시지 발행 확인. channel이 `LOG` |

### Consumer Group 확인

1. 좌측 메뉴에서 **Consumer Groups** 클릭
2. 세 개의 Consumer Group 확인:
   - `payment-service-group` — `order-events` 토픽 구독
   - `order-service-group` — `payment-events` 토픽 구독
   - `notification-service-group` — `payment-events` 토픽 구독
3. **LAG**가 `0`이면 모든 메시지가 정상 소비된 것

---

## 시나리오 4: Eureka 서비스 등록 확인

1. http://localhost:8761 접속
2. **Instances currently registered with Eureka** 섹션 확인
3. 다음 서비스가 모두 **UP** 상태인지 확인:
   - `ORDER-SERVICE`
   - `PAYMENT-SERVICE`
   - `NOTIFICATION-SERVICE`
   - `API-GATEWAY`

**curl로 확인:**
```bash
curl -s http://localhost:8761/eureka/apps | grep '<name>'
```

---

## 시나리오 5: 장애 복구 테스트

### 5-1. Payment Service 중단 후 재시작

```bash
# Payment Service 중단
docker compose stop payment-service

# 주문 생성 (결제 처리 불가 → PENDING 상태 유지)
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"productName":"테스트","quantity":1,"price":5000}'

# 주문 상태 확인 → PENDING
curl http://localhost:8080/api/orders

# Payment Service 재시작
docker compose start payment-service

# 30초 후 주문 상태 재확인 → CONFIRMED 또는 CANCELLED
sleep 30
curl http://localhost:8080/api/orders
```

**확인 포인트:**
- Kafka가 메시지를 보존하므로, Payment Service 재시작 후 밀린 메시지를 소비하여 주문 상태가 업데이트됨
- 이것이 이벤트 기반 아키텍처의 핵심 장점: **서비스 간 시간적 결합(temporal coupling)이 없음**

### 5-2. DB 직접 확인

```bash
# PostgreSQL 접속
docker exec -it postgresql psql -U postgres

# 주문 DB 확인
\c order_db
SELECT id, product_name, status, created_at FROM orders ORDER BY id;

# 결제 DB 확인
\c payment_db
SELECT id, order_id, amount, status, created_at FROM payments ORDER BY id;
```

---

## 시나리오 6: Swagger UI 통합 테스트

Gateway의 통합 Swagger UI를 통해 모든 서비스의 API를 한 화면에서 테스트할 수 있다.

### 접속 방법

1. http://localhost:8080/webjars/swagger-ui/index.html 접속
2. 상단 드롭다운에서 서비스 선택:
   - **Order Service** — 주문 생성/조회 API
   - **Payment Service** — 결제 조회 API

### API 목록

| 서비스 | 메서드 | 경로 | 설명 |
|--------|--------|------|------|
| Order | `POST` | `/api/orders` | 주문 생성 → Kafka 이벤트 발행 |
| Order | `GET` | `/api/orders/{id}` | 주문 단건 조회 |
| Order | `GET` | `/api/orders` | 전체 주문 목록 조회 |
| Payment | `GET` | `/api/payments/order/{orderId}` | 주문별 결제 정보 조회 |

### 테스트 순서

1. Order Service 선택 → `POST /api/orders` → Try it out → 주문 생성
2. 2~3초 대기
3. Order Service → `GET /api/orders/{id}` → 상태 변경 확인
4. Payment Service 선택 → `GET /api/payments/order/{orderId}` → 결제 결과 확인

---

## 환경 정리

```bash
# 컨테이너 중지
docker compose down

# 컨테이너 + 데이터 볼륨 삭제 (DB 초기화)
docker compose down -v
```

---

[← 이전: 10. 트러블슈팅](10-troubleshooting.md) | [목차로 돌아가기 →](README.md)
