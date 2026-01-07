# 09. Docker 환경 구성

## 왜 Docker인가

이 프로젝트는 8개의 컨테이너가 필요하다. 로컬에 PostgreSQL, Kafka, Java를 각각 설치하고 설정하는 것은 번거롭다. Docker Compose를 사용하면 `docker compose up` 한 줄로 전체 환경을 구성할 수 있다.

---

## docker-compose.yml 구조

```yaml
services:
  # 인프라 (먼저 기동)
  postgresql:    # DB
  kafka:         # 메시지 브로커
  kafka-ui:      # 모니터링 (kafka에 의존)

  # Spring Cloud (인프라 다음)
  eureka-server: # 서비스 디스커버리

  # 마이크로서비스 (eureka 다음)
  order-service:
  payment-service:
  notification-service:

  # 게이트웨이 (eureka 다음)
  api-gateway:
```

---

## 기동 순서 제어

`depends_on`과 `healthcheck`로 컨테이너 기동 순서를 제어한다.

### Healthcheck 설정

각 인프라 컨테이너가 **실제로 준비되었는지** 확인:

```yaml
postgresql:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U postgres"]
    interval: 5s
    timeout: 5s
    retries: 10
```

```yaml
kafka:
  healthcheck:
    test: ["CMD-SHELL",
      "/opt/kafka/bin/kafka-broker-api-versions.sh \
       --bootstrap-server localhost:9092 > /dev/null 2>&1"]
    interval: 10s
    timeout: 10s
    retries: 15
    start_period: 30s    # Kafka 초기화에 시간이 걸림
```

```yaml
eureka-server:
  healthcheck:
    test: ["CMD-SHELL", "curl -f http://localhost:8761/actuator/health || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 10
    start_period: 30s
```

### depends_on + condition

```yaml
order-service:
  depends_on:
    postgresql:
      condition: service_healthy     # DB가 준비된 후
    kafka:
      condition: service_healthy     # Kafka가 준비된 후
    eureka-server:
      condition: service_healthy     # Eureka가 준비된 후
```

### 실제 기동 순서

```
Phase 1: postgresql, kafka            (동시 시작)
Phase 2: eureka-server, kafka-ui      (Phase 1 healthy 후)
Phase 3: order, payment, notification (Phase 2 healthy 후)
Phase 4: api-gateway                  (eureka healthy 후)
```

---

## 멀티스테이지 Dockerfile

각 Spring Boot 서비스는 두 단계로 빌드된다:

```dockerfile
# Stage 1: Gradle 빌드 (큰 이미지, 빌드 도구 포함)
FROM gradle:8.7-jdk17 AS build
WORKDIR /workspace
COPY src src
RUN printf '...' > settings.gradle && \
    printf '...' > build.gradle && \
    gradle bootJar --no-daemon -x test

# Stage 2: 실행 (작은 이미지, JRE만 포함)
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 왜 멀티스테이지인가?

| | 단일 스테이지 | 멀티스테이지 |
|---|-------------|------------|
| 이미지 크기 | ~800MB (JDK + Gradle + 소스) | ~200MB (JRE + JAR만) |
| 보안 | 소스코드, 빌드 도구 포함 | 실행 파일만 포함 |
| 시작 속도 | 느림 | 빠름 |

### build.gradle을 Dockerfile 안에서 생성하는 이유

이 프로젝트는 Gradle 멀티모듈 구조이지만, Docker에서는 **각 서비스를 독립적으로 빌드**한다. Docker 빌드 컨텍스트가 각 서비스의 디렉토리이므로, 루트의 `build.gradle`에 접근할 수 없다.

그래서 Dockerfile 안에서 `printf`로 독립적인 `build.gradle`을 생성한다. 플러그인 버전과 의존성을 직접 명시하여 루트 빌드 파일 없이 빌드한다.

---

## Kafka (KRaft 모드) 설정

```yaml
kafka:
  image: apache/kafka:3.7.0
  environment:
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
    KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
    CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk
```

### 주요 설정 설명

| 설정 | 값 | 설명 |
|------|-----|------|
| `PROCESS_ROLES` | `broker,controller` | 브로커 + 컨트롤러 겸용 (단일 노드) |
| `LISTENERS` | `PLAINTEXT:9092, CONTROLLER:9093` | 클라이언트용(9092), 컨트롤러용(9093) 리스너 |
| `ADVERTISED_LISTENERS` | `PLAINTEXT://kafka:9092` | 다른 컨테이너가 접근할 주소 |
| `QUORUM_VOTERS` | `1@kafka:9093` | KRaft 투표자 (자기 자신) |
| `REPLICATION_FACTOR` | `1` | 단일 노드이므로 복제본 1개 |
| `CLUSTER_ID` | `MkU3OEV...` | KRaft 클러스터 식별자 (고정값) |

### ADVERTISED_LISTENERS가 중요한 이유

`LISTENERS`는 Kafka가 **바인드**하는 주소, `ADVERTISED_LISTENERS`는 Kafka가 클라이언트에게 **알려주는** 주소이다.

Docker 환경에서 `ADVERTISED_LISTENERS`를 `kafka:9092`로 설정하면, Spring Boot 서비스들이 Docker 네트워크 안에서 `kafka`라는 호스트명으로 접근할 수 있다.

---

## PostgreSQL 초기화

```yaml
postgresql:
  image: postgres:16-alpine
  environment:
    POSTGRES_USER: postgres
    POSTGRES_PASSWORD: postgres
  volumes:
    - postgres-data:/var/lib/postgresql/data
    - ./init-db.sql:/docker-entrypoint-initdb.d/init-db.sql
```

### init-db.sql

```sql
CREATE DATABASE order_db;
CREATE DATABASE payment_db;
```

`/docker-entrypoint-initdb.d/` 디렉토리에 SQL 파일을 넣으면, PostgreSQL 컨테이너가 **최초 시작 시** 자동으로 실행한다. 이미 데이터가 있는 볼륨이면 실행하지 않는다.

### 볼륨

```yaml
volumes:
  postgres-data:
```

`docker compose down`으로 컨테이너를 내려도 데이터는 보존된다. `docker compose down -v`로 볼륨까지 삭제하면 데이터가 초기화된다.

---

## Kafka UI 설정

```yaml
kafka-ui:
  image: provectuslabs/kafka-ui:latest
  ports:
    - "9090:8080"    # 호스트 9090 → 컨테이너 8080
  environment:
    KAFKA_CLUSTERS_0_NAME: local
    KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
```

http://localhost:9090 에서 접근. Kafka의 토픽, 메시지, Consumer Group을 웹 UI로 모니터링할 수 있다.

---

## 환경 변수 오버라이드

docker-compose.yml에서 환경 변수로 application.yml의 설정을 오버라이드한다:

```yaml
order-service:
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgresql:5432/order_db
    SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://eureka-server:8761/eureka/
```

Spring Boot는 환경 변수를 자동으로 프로퍼티에 매핑한다:
- `SPRING_DATASOURCE_URL` → `spring.datasource.url`
- `SPRING_KAFKA_BOOTSTRAP_SERVERS` → `spring.kafka.bootstrap-servers`

이렇게 하면 application.yml은 변경 없이, Docker 환경에서만 다른 값을 사용할 수 있다.

---

## 유용한 Docker 명령어

```bash
# 전체 기동 (빌드 포함)
docker compose up -d --build

# 특정 서비스만 재빌드 + 재시작
docker compose build order-service
docker compose up -d order-service

# 전체 상태 확인
docker compose ps

# 로그 확인 (실시간)
docker compose logs -f

# 특정 서비스 로그
docker compose logs -f notification-service

# 컨테이너 안에서 명령 실행
docker exec -it kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list

# 토픽 메시지 확인 (콘솔 컨슈머)
docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic order-events --from-beginning

# 종료
docker compose down

# 종료 + 데이터 초기화
docker compose down -v

# 종료 + 이미지 삭제
docker compose down -v --rmi all
```

---

[← 이전: 08. Kafka 이벤트 설계](08-kafka-event-flow.md) | [다음: 10. 트러블슈팅 →](10-troubleshooting.md)
