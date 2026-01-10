# 10. 트러블슈팅

이 프로젝트를 개발하면서 만난 실제 문제와 해결 과정을 기록한다.

---

## 1. ClassNotFoundException: 크로스 서비스 역직렬화 실패

### 증상

```
org.springframework.messaging.converter.MessageConversionException:
  failed to resolve class name.
  Class not found [com.example.order.event.OrderCreatedEvent]
```

Payment Service가 `order-events` 토픽의 메시지를 읽으려 할 때 발생.

### 원인

Spring Kafka의 `JsonSerializer`가 메시지를 보낼 때 `__TypeId__` 헤더에 Producer 측의 **클래스 전체 경로(FQCN)**를 포함한다.

```
Producer (Order Service):
  __TypeId__ = "com.example.order.event.OrderCreatedEvent"

Consumer (Payment Service):
  이 클래스를 찾으려고 함 → 없음! → ClassNotFoundException
  Payment Service에는 "com.example.payment.event.OrderCreatedEvent"가 있음
```

### 해결

`spring.json.type.mapping`으로 논리적 alias를 사용:

```yaml
# Order Service (Producer)
spring.json.type.mapping: orderCreated:com.example.order.event.OrderCreatedEvent

# Payment Service (Consumer)
spring.json.type.mapping: orderCreated:com.example.payment.event.OrderCreatedEvent
```

이제 `__TypeId__` 헤더에 FQCN 대신 `orderCreated`가 들어가고, Consumer가 이 alias를 자기 패키지의 클래스에 매핑한다.

### 교훈

마이크로서비스에서 Kafka JSON 직렬화를 사용할 때, **반드시 type mapping을 설정**해야 한다. 그렇지 않으면 모든 서비스가 같은 이벤트 클래스 패키지를 공유해야 하는데, 이는 마이크로서비스 원칙에 위배된다.

---

## 2. 기존 토픽에 잘못된 타입 헤더 잔류

### 증상

Type mapping을 추가한 후에도 여전히 역직렬화 실패.

### 원인

기존에 type mapping 없이 발행된 메시지가 토픽에 남아있었다. 이 메시지들의 `__TypeId__` 헤더에는 FQCN이 들어있어서, 새로운 Consumer 설정으로도 역직렬화 불가.

### 해결

기존 토픽을 삭제하고, 서비스 재시작 후 새 메시지로 테스트:

```bash
# 토픽 삭제
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --delete --topic order-events

docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --delete --topic payment-events
```

### 교훈

Kafka 메시지는 보존되므로, 직렬화 설정을 변경한 후에는 **기존 메시지와의 호환성**을 고려해야 한다. 개발 환경에서는 토픽을 삭제하면 되지만, 프로덕션에서는 호환 가능한 방식으로 마이그레이션해야 한다.

---

## 3. API Gateway 503 Service Unavailable

### 증상

```
curl -X POST http://localhost:8080/api/orders ...
→ 503 Service Unavailable
```

서비스가 실행 중인데도 Gateway를 통한 요청이 실패.

### 원인

Spring Boot 서비스가 시작되어도 **Eureka에 등록되기까지 시간이 걸린다**:
1. 서비스 시작: ~10초
2. Eureka에 첫 등록: ~30초 (heartbeat 주기)
3. Gateway가 새 인스턴스를 인식: ~30초 (레지스트리 캐시 갱신)

### 해결

서비스 기동 후 충분히 기다린다 (약 30~60초). 또는 서비스에 직접 요청하여 확인:

```bash
# Gateway 우회, 직접 Order Service에 요청
curl -X POST http://localhost:8081/api/orders ...

# Eureka에 등록되었는지 확인
curl http://localhost:8761/eureka/apps | grep ORDER-SERVICE
```

### 교훈

Eureka 기반 시스템에서는 서비스 등록/인식에 시간이 걸린다. 프로덕션에서는 `eureka.instance.lease-renewal-interval-in-seconds`와 `eureka.client.registry-fetch-interval-seconds` 값을 조정하여 갱신 주기를 단축할 수 있다.

---

## 4. Gradle 빌드 시 ${springCloudVersion} 변수 문제

### 증상

Dockerfile에서 COPY heredoc으로 build.gradle을 생성할 때 `${springCloudVersion}` 변수가 셸에 의해 해석됨.

### 원인

Dockerfile의 `COPY <<'BUILD'` heredoc 내에서 `${}` 구문이 Gradle 변수가 아닌 셸 변수로 해석될 수 있음.

### 해결

Dockerfile에서 `printf` 명령으로 build.gradle을 생성하고, Gradle 변수 대신 **버전을 직접 하드코딩**:

```dockerfile
RUN printf '...\n\
dependencyManagement {\n\
    imports { mavenBom "org.springframework.cloud:spring-cloud-dependencies:2023.0.1" }\n\
}\n...' > build.gradle
```

### 교훈

Docker 빌드 컨텍스트에서 빌드 파일을 동적으로 생성할 때는, 셸 변수 치환에 주의해야 한다. 가능하면 변수 없이 값을 직접 명시하는 것이 안전하다.

---

## 5. Docker 네트워크에서 hostname 접근 불가

### 증상

Eureka에 등록된 서비스가 컨테이너 ID(예: `a3f2b1c9d4e5`)로 등록되어, 다른 서비스에서 접근 불가.

### 해결

```yaml
eureka:
  instance:
    prefer-ip-address: true
```

hostname 대신 IP 주소로 등록하도록 설정. Docker 네트워크 안에서는 IP로 서로 접근 가능.

---

## 일반적인 디버깅 팁

### 1. 서비스 로그 확인

```bash
# 문제가 있는 서비스 로그 확인
docker compose logs -f payment-service

# 에러만 필터링
docker compose logs payment-service 2>&1 | grep ERROR
```

### 2. Kafka 토픽/메시지 확인

```bash
# 토픽 목록
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list

# 토픽의 메시지 확인 (처음부터)
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic order-events --from-beginning

# Consumer Group 상태 (lag 확인)
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group payment-service-group --describe
```

### 3. DB 직접 확인

```bash
# PostgreSQL 접속
docker exec -it postgresql psql -U postgres -d order_db

# 주문 목록 조회
SELECT * FROM orders;

# 결제 목록 조회
\c payment_db
SELECT * FROM payments;
```

### 4. Eureka 등록 상태 확인

```bash
# 등록된 서비스 목록
curl -s http://localhost:8761/eureka/apps | grep '<name>'

# 특정 서비스의 인스턴스 정보
curl -s http://localhost:8761/eureka/apps/ORDER-SERVICE
```

### 5. 전체 재시작

문제가 복잡할 때는 깨끗하게 재시작:

```bash
docker compose down -v          # 컨테이너 + 볼륨 삭제
docker compose up -d --build    # 재빌드 + 재시작
```

---

[← 이전: 09. Docker 환경 구성](09-docker-setup.md) | [다음: 11. 테스트 시나리오 가이드 →](11-testing.md)
