# 🛠️ 통합 트러블슈팅 로그 (Troubleshooting Log)

이 문서는 프로젝트 개발 및 운영 과정에서 발생한 주요 장애 상황, 버그, 기술적 병목 현상과 그 해결 과정을 기록합니다.

---

## 🏗️ 가상 인프라 및 환경 (Infrastructure & Environment)

### [2026-02-27] Gradlew 실행 파일 누락
- **문제:** `gradlew` 스크립트가 없어 CLI 빌드가 불가능함.
- **해결:** `gradlew` 스크립트를 수동 생성하고 실행 권한을 부여하여 해결.

### [2026-02-27] DB 연결 (R2DBC) 타임아웃
- **문제:** Docker 네트워크 환경에서 초기 DB 연결 시 간헐적 타임아웃 발생.
- **해결:** Docker Compose의 `healthcheck`를 도입하고, 연결 문자열에 `connectTimeout` 옵션을 추가하여 안정성 확보.

### [2026-03-14] 포트 충돌 (Port Conflict)
- **문제:** 8082 포트가 이미 사용 중이라 서비스 기동 실패.
- **해결:** 해당 포트를 점유 중인 프로세스를 찾아 종료 처리.

---

## ⚙️ 백엔드 통합 및 서비스 (Backend Integration)

### [2026-03-14] LLM 응답 역직렬화 (JSON Parsing) 이슈
- **문제:** Ollama 모델이 간헐적으로 단일 객체와 리스트 구조를 섞어서 응답하거나 텍스트를 포함함.
- **해결:** 응답의 시작 문자를 확인하여 단일/리스트 구조를 분기 처리하는 유연한 파싱 로직을 `OllamaAnalyzerService`에 도입.

### [2026-03-10] 치지직 Open API 인증 스펙 오해
- **문제:** OAuth Flow(Bearer)를 시도했으나 지속적인 500 에러 발생.
- **해결:** 공식 문서를 재검토하여 B2B API는 `Client-Id`와 `Client-Secret` 헤더를 직접 주입하는 Client Auth 방식임을 확인 후 리팩토링.

### [2026-03-09] Redis 연결 예외 (Connection Exception)
- **문제:** Redis 다운 시 Core API 전체가 장애로 전파됨.
- **해결:** `onErrorResume` Fallback 로직을 추가하여 Redis 접속 실패 시 빈 값을 반환하도록 방어 코드를 작성.

### [2026-03-05] Socket.IO 의존성 임포트 에러
- **문제:** `io.socket` 패키지를 찾지 못해 빌드 실패.
- **해결:** `build.gradle`에 정확한 `socket.io-client` 의존성을 추가하고 Gradle 리프레시 수행.

### [2026-03-02] R2DBC 엔티티와 스키마 불일치
- **문제:** Java 엔티티에 추가된 필드가 DB 스키마에 미반영되어 SQL 에러 발생.
- **해결:** `schema.sql`을 최신화하고 Docker 볼륨 초기화를 통해 스키마 재동기화.

### [2026-02-27] Reactor-Kafka 버전 충돌
- **문제:** `reactor-kafka`와 Kafka 4.x 클라이언트 간 생성자 서명 불일치로 런타임 에러.
- **해결:** `reactor-kafka`를 제거하고 `@KafkaListener` 배치 모드(Batch Mode)를 사용하여 리액티브 파이프라인 우회 구현.

---

## 📡 통신 및 데이터 아키텍처 (Communication & Data)

### [2026-03-14] 데이터 전수 리팩토링 및 Import Hell
- **문제:** 공통 모듈(`common`)로 DTO를 이동시킨 후 프로젝트 전체에서 수백 개의 임포트 에러 발생.
- **해결:** IDE 기능을 활용한 일괄 리팩토링 및 `build.gradle` 의존성 계층 구조 재정비.

### [2026-03-02] Kafka `__TypeId__` 헤더 역직렬화 에러
- **문제:** 메시지 헤더의 클래스 패스 정보가 모듈 간 달라 역직렬화 실패.
- **해결:** `ADD_TYPE_INFO_HEADERS`를 `false`로 설정하고 수신측에서 기본 타입을 명시하도록 설정.

### [2026-02-27] SSE 페이로드 유실 (Payload Size)
- **문제:** `multicast()` 처리 시 데이터 유실 현상 발생.
- **해결:** `replay(100)`를 적용하여 최근 데이터를 버퍼링하고 JSON 타입 헤더 비활성화를 통해 안정적 스트리밍 구현.

---

## 💻 프론트엔드 (Frontend)

### [2026-03-09] 프로퍼티 참조 오류 (Undefined Property)
- **문제:** API 응답 구조 변경으로 인해 프론트엔드에서 `charAt` 등을 호출할 때 에러 발생.
- **해결:** 옵셔널 체이닝(`?.`) 및 방어적 매핑 로직을 추가하여 데이터 미도착 시에도 렌더링이 깨지지 않도록 수정.
