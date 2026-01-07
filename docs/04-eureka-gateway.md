# 04. Eureka + Gateway 구현 상세

## Eureka Server 구현

### 프로젝트 구조

```
eureka-server/
├── Dockerfile
├── build.gradle
└── src/main/
    ├── java/com/example/eureka/
    │   └── EurekaServerApplication.java
    └── resources/
        └── application.yml
```

### 의존성

```gradle
dependencies {
    implementation 'org.springframework.cloud:spring-cloud-starter-netflix-eureka-server'
}
```

이 하나의 의존성에 Eureka 서버를 실행하는 데 필요한 모든 것이 포함되어 있다:
- Netflix Eureka Server
- Spring Boot Auto-configuration
- 내장 대시보드 (웹 UI)

### 메인 클래스

```java
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

`@EnableEurekaServer`가 핵심이다. 이 어노테이션이 하는 일:
1. Eureka 서버 자동 구성
2. 서비스 레지스트리 초기화
3. REST API 엔드포인트 활성화 (`/eureka/apps`)
4. 대시보드 웹 UI 활성화

### 설정 (application.yml)

```yaml
server:
  port: 8761          # Eureka 기본 포트 (관례)

eureka:
  client:
    register-with-eureka: false   # 자기 자신은 등록 안 함
    fetch-registry: false         # 다른 Eureka에서 정보 안 가져옴
  server:
    enable-self-preservation: false  # 개발 환경에서 self-preservation 비활성화
```

#### self-preservation이란?

Eureka는 네트워크 장애 시 서비스를 성급하게 제거하지 않도록 **자기 보호 모드**를 가지고 있다. heartbeat가 일정 비율 이하로 떨어지면 "네트워크 문제겠지"라고 판단하고 서비스를 유지한다.

개발 환경에서는 컨테이너를 자주 재시작하므로, 이 기능을 끄는 것이 편하다.

---

## API Gateway 구현

### 프로젝트 구조

```
api-gateway/
├── Dockerfile
├── build.gradle
└── src/main/
    ├── java/com/example/gateway/
    │   └── ApiGatewayApplication.java
    └── resources/
        └── application.yml
```

### 의존성

```gradle
dependencies {
    implementation 'org.springframework.cloud:spring-cloud-starter-gateway'
    implementation 'org.springframework.cloud:spring-cloud-starter-netflix-eureka-client'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
}
```

주의: `spring-boot-starter-web`이 **없다**. Spring Cloud Gateway는 WebFlux(Netty) 기반이므로, `starter-web`과 함께 사용하면 충돌이 발생한다.

### 메인 클래스

```java
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

### 라우팅 설정 상세

```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**
        - id: payment-service
          uri: lb://payment-service
          predicates:
            - Path=/api/payments/**
```

#### 각 필드 설명

| 필드 | 설명 | 예시 |
|------|------|------|
| `id` | 라우트 식별자 (고유해야 함) | `order-service` |
| `uri` | 라우팅 대상. `lb://`는 Eureka 기반 로드밸런싱 | `lb://order-service` |
| `predicates` | 이 라우트를 적용할 조건 | `Path=/api/orders/**` |

#### 라우팅 동작 흐름

```
1. 클라이언트 요청: POST http://localhost:8080/api/orders
                                                 ↓
2. Gateway가 등록된 라우트를 순서대로 검사
   - id=order-service: Path=/api/orders/** → 매칭!
                                                 ↓
3. uri=lb://order-service
   - "lb://" → Eureka에서 order-service 인스턴스 목록 조회
   - 결과: [192.168.1.5:8081]
                                                 ↓
4. 실제 요청: POST http://192.168.1.5:8081/api/orders
                                                 ↓
5. 응답을 클라이언트에게 전달
```

#### discovery.locator.enabled의 역할

명시적 라우트 외에, Eureka에 등록된 **모든 서비스를 자동으로 라우팅**할 수 있다:

```
# 자동 라우팅 (서비스 이름으로)
http://localhost:8080/order-service/api/orders
http://localhost:8080/payment-service/api/payments

# 명시적 라우트 (위에 정의한 것)
http://localhost:8080/api/orders       ← 이걸 사용
http://localhost:8080/api/payments     ← 이걸 사용
```

명시적 라우트가 있으면 자동 라우트보다 **우선**한다.

---

## Eureka 클라이언트 설정 (모든 서비스 공통)

각 마이크로서비스는 Eureka 클라이언트로 동작한다:

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka/
  instance:
    prefer-ip-address: true
```

### prefer-ip-address: true가 필요한 이유

Docker 컨테이너 안에서는 hostname이 컨테이너 ID(예: `a3f2b1c9d4e5`)로 설정된다. 다른 컨테이너에서 이 hostname으로 접근할 수 없다.

`prefer-ip-address: true`를 설정하면 hostname 대신 **컨테이너의 IP 주소**를 Eureka에 등록한다. Docker 네트워크 안에서는 IP로 서로 접근할 수 있으므로, 이 설정이 필수다.

---

## 서비스 등록/발견 흐름 정리

```
[시작 시]
Order Service → Eureka에 등록: "order-service, 172.18.0.5:8081"
Payment Service → Eureka에 등록: "payment-service, 172.18.0.6:8082"
Notification Service → Eureka에 등록: "notification-service, 172.18.0.7:8083"
API Gateway → Eureka에 등록: "api-gateway, 172.18.0.8:8080"

[30초마다]
각 서비스 → Eureka에 heartbeat 전송: "나 아직 살아있어"

[요청 시]
Client → API Gateway
API Gateway → Eureka: "order-service 어디있어?"
Eureka → API Gateway: "172.18.0.5:8081에 있어"
API Gateway → Order Service (172.18.0.5:8081)
```

---

[← 이전: 03. Kafka 핵심 개념](03-kafka-basics.md) | [다음: 05. Order Service 상세 →](05-order-service.md)
