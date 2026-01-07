# 02. Spring Cloud 개념 정리

## Spring Cloud란?

Spring Cloud는 마이크로서비스 아키텍처를 구축하기 위한 **도구 모음**이다. 분산 시스템에서 공통적으로 필요한 패턴들을 Spring Boot 위에서 쉽게 구현할 수 있게 해준다.

이 프로젝트에서 사용하는 Spring Cloud 컴포넌트:

| 컴포넌트 | 역할 | 해결하는 문제 |
|----------|------|---------------|
| Eureka | 서비스 디스커버리 | "Payment Service가 어디서 실행 중이지?" |
| Gateway | API 게이트웨이 | "클라이언트가 각 서비스 주소를 다 알아야 하나?" |

---

## 서비스 디스커버리 (Eureka)

### 문제: 서비스 위치를 어떻게 알 수 있는가?

마이크로서비스는 여러 인스턴스가 동적으로 생성/삭제된다. IP와 포트가 계속 바뀌는 환경에서, A 서비스가 B 서비스를 호출하려면 **B의 현재 위치**를 알아야 한다.

하드코딩하면?

```yaml
# 이렇게 하면 안 된다
payment-service:
  url: http://192.168.1.100:8082  # IP가 바뀌면?
```

### 해결: 서비스 레지스트리

Eureka는 **전화번호부** 같은 역할을 한다:

```
1. 각 서비스가 시작할 때 Eureka에 자기 정보를 등록한다
   "나는 order-service이고, 192.168.1.50:8081에서 실행 중이야"

2. 다른 서비스를 찾고 싶으면 Eureka에 질문한다
   "payment-service 어디있어?" → "192.168.1.60:8082에 있어"

3. 주기적으로 heartbeat를 보내 살아있음을 알린다
   heartbeat가 끊기면 Eureka가 해당 서비스를 목록에서 제거한다
```

### 코드에서 어떻게 동작하는가

**Eureka 서버** (`eureka-server/`):

```java
@SpringBootApplication
@EnableEurekaServer        // ← 이 어노테이션 하나로 Eureka 서버가 된다
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

```yaml
eureka:
  client:
    register-with-eureka: false   # 자기 자신은 등록하지 않음
    fetch-registry: false         # 자기 자신은 레지스트리를 가져오지 않음
```

**Eureka 클라이언트** (모든 서비스):

```java
@SpringBootApplication
@EnableDiscoveryClient     // ← 이 어노테이션으로 Eureka에 자동 등록
public class OrderServiceApplication { ... }
```

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka/  # Eureka 서버 주소
  instance:
    prefer-ip-address: true   # Docker 환경에서 hostname 대신 IP 사용
```

### Eureka 대시보드

http://localhost:8761 에 접속하면 등록된 서비스 목록을 볼 수 있다:

```
Application          Status
ORDER-SERVICE        UP(1)
PAYMENT-SERVICE      UP(1)
NOTIFICATION-SERVICE UP(1)
API-GATEWAY          UP(1)
```

---

## API 게이트웨이 (Spring Cloud Gateway)

### 문제: 클라이언트가 모든 서비스 주소를 알아야 하는가?

서비스가 5개, 10개, 100개로 늘어나면? 클라이언트가 각 서비스의 주소와 포트를 모두 관리해야 한다면 매우 불편하다.

```
# 이렇게 하면 안 된다
주문: http://192.168.1.50:8081/api/orders
결제: http://192.168.1.60:8082/api/payments
알림: http://192.168.1.70:8083/api/notifications
```

### 해결: 단일 진입점

API Gateway는 **모든 요청의 단일 진입점**이다. 클라이언트는 Gateway 주소만 알면 된다:

```
클라이언트는 오직 http://localhost:8080 만 안다

/api/orders/**   → order-service로 라우팅
/api/payments/** → payment-service로 라우팅
```

### 라우팅 설정

```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true                # Eureka에 등록된 서비스 자동 라우팅
          lower-case-service-id: true  # 서비스 이름 소문자 사용
      routes:
        - id: order-service
          uri: lb://order-service      # lb:// = Eureka 기반 로드밸런싱
          predicates:
            - Path=/api/orders/**      # 이 경로로 오면
        - id: payment-service
          uri: lb://payment-service
          predicates:
            - Path=/api/payments/**
```

### `lb://` 프로토콜의 의미

`lb://order-service`에서:
- `lb` = Load Balancer
- `order-service` = Eureka에 등록된 서비스 이름

Gateway가 Eureka에서 `order-service`의 실제 주소를 조회하고, 여러 인스턴스가 있으면 라운드로빈으로 로드밸런싱한다.

```
요청: GET http://localhost:8080/api/orders/1

Gateway 내부 동작:
1. Path=/api/orders/** 매칭 → order-service 라우트 선택
2. lb://order-service → Eureka에서 조회 → 192.168.1.50:8081
3. 실제 요청: GET http://192.168.1.50:8081/api/orders/1
4. 응답을 클라이언트에게 전달
```

---

## Spring Cloud Gateway vs Zuul

Spring Cloud Gateway는 Zuul의 후속 프로젝트이다:

| | Zuul 1 | Spring Cloud Gateway |
|---|--------|---------------------|
| I/O 모델 | 블로킹 (Servlet) | 논블로킹 (Netty, WebFlux) |
| 성능 | 상대적으로 낮음 | 높은 처리량 |
| Spring Boot | 2.x까지 | 3.x 지원 |
| 상태 | 유지보수 모드 | 활발히 개발 중 |

이 프로젝트에서 Gateway는 **WebFlux 기반**으로 동작하므로, `spring-boot-starter-web` 대신 `spring-cloud-starter-gateway`를 사용한다 (내부적으로 WebFlux 포함).

---

[← 이전: 01. 아키텍처 설계](01-architecture.md) | [다음: 03. Apache Kafka 핵심 개념 →](03-kafka-basics.md)
