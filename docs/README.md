# Spring Cloud + Kafka 예제 프로젝트 — 기술 문서

이 문서는 Spring Cloud 마이크로서비스 환경에서 Apache Kafka를 활용한 이벤트 기반 아키텍처를 **A부터 Z까지** 설명합니다.

번호 순서대로 읽으면 프로젝트 전체를 이해할 수 있도록 구성했습니다.

---

## 목차

| # | 문서 | 설명 |
|---|------|------|
| 00 | [프로젝트 개요](00-overview.md) | 왜 이 프로젝트를 만들었는지, 무엇을 학습할 수 있는지 |
| 01 | [아키텍처 설계](01-architecture.md) | 전체 시스템 구조, 서비스 간 관계, 데이터 흐름 |
| 02 | [Spring Cloud 개념 정리](02-spring-cloud.md) | Eureka, Gateway, Discovery 등 Spring Cloud 핵심 개념 |
| 03 | [Apache Kafka 핵심 개념](03-kafka-basics.md) | Topic, Partition, Consumer Group, Offset 등 Kafka 기초 |
| 04 | [Eureka + Gateway 구현](04-eureka-gateway.md) | 서비스 디스커버리와 API 게이트웨이 구현 상세 |
| 05 | [Order Service 상세](05-order-service.md) | 주문 서비스 코드 분석 — Entity, Repository, Controller, Kafka Producer |
| 06 | [Payment Service 상세](06-payment-service.md) | 결제 서비스 코드 분석 — Kafka Consumer/Producer, 결제 시뮬레이션 |
| 07 | [Notification Service 상세](07-notification-service.md) | 알림 서비스 코드 분석 — Kafka Consumer, 알림 로직 |
| 08 | [Kafka 이벤트 설계](08-kafka-event-flow.md) | 토픽 설계, 이벤트 클래스, Type Mapping, Consumer Group 전략 |
| 09 | [Docker 환경 구성](09-docker-setup.md) | docker-compose.yml 분석, 멀티스테이지 빌드, 기동 순서 |
| 10 | [트러블슈팅](10-troubleshooting.md) | 개발 중 만난 문제와 해결 과정 |

---

## 읽는 순서 추천

**처음 접하는 경우**: 00 → 02 → 03 → 01 → 04~07 → 08 → 09

**Spring Cloud는 알고, Kafka를 배우려는 경우**: 00 → 03 → 08 → 05~07

**바로 실행해보고 싶은 경우**: [README.md](../README.md)의 사용법 섹션 참고
