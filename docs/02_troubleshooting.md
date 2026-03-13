# Consolidated Troubleshooting Log

이 문서는 프로젝트 개발 과정에서 발생한 주요 트러블슈팅 사례를 통합 기록합니다.

---

## 🛠️ Infrastructure & Environment
- **[2026-02-27] Gradlew Missing**: `gradlew` 스크립트 수동 생성 및 실행 권한 부여.
- **[2026-03-14] Port Conflict**: 8082 포트 이미 사용 중 에러 해결 (프로세스 종료).

## 🛠️ Backend Integration
- **[2026-02-27] Reactor-Kafka Version Conflict**: Kafka 4.x 호환성 문제로 `reactor-kafka` 제거 후 `@KafkaListener` 배치 모드 전환.
- **[2026-02-27] Redis Bean Conflict**: 직접 등록한 `LettuceConnectionFactory`와 스프링 자동 설정 충돌 해결.
- **[2026-03-09] Redis Connection Exception**: Redis 다운 시 Core API 장애 전파 방지를 위해 `onErrorResume` Fallback 추가.
- **[2026-03-10] Chzzk Auth Spec**: 공식 API 연동 시 OAuth Flow가 아닌 직접적인 Client Auth 헤더 주입 방식으로 수정.

## 🛠️ Communication & DTO
- **[2026-02-27] SSE Payload Issue**: `multicast()` 데이터 유실 해결을 위해 `replay(100)` 적용 및 JSON 타입 헤더 비활성화.
- **[2026-03-14] Import Hell**: `common` 모듈로 DTO 통합 시 발생한 프로젝트 전체 Import 오류 및 `build.gradle` 의존성 일괄 정비.

## 🛠️ Frontend
- **[2026-03-09] Undefined Property**: API 응답 구조 변경에 따른 프런트엔드 매핑 오류를 Optional Chaining으로 해결.
