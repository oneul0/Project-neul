# Technical Overview & Component Changes

이 문서는 최근 리팩토링 및 기능 개발 과정에서 변경된 주요 컴포넌트의 기술적 상세 내용을 기록합니다.

---

## 1. Backend: Micro-batch Architecture

### [Collector Module]
- **`NidChatCollector`**: 치지직 채팅 채널 ID 및 엑세스 토큰을 조회한 후, `wss://kr-ss1.chat.naver.com/chat`에 연결하여 실시간 데이터를 수집합니다.
- **Batching Scheme**: `Sinks.Many`를 통해 들어온 메시지를 `window(Duration.ofMinutes(1))`로 묶어 `RawChatBatch` 객체로 Kafka에 발행합니다.
- **Topic Migration**: `raw-chat-topic`에서 `raw-chat-batch-topic`으로 전환하여 네트워크 오버헤드를 감소시켰습니다.

### [Analyzer Module]
- **`ChatAnalysisProcessor`**: Kafka에서 전달받은 JSON 리스트를 Java 리스트로 역직렬화한 후, 벌크 분석을 수행합니다.
- **Ollama Integration**: 프롬프트를 튜닝하여 여러 채팅 메시지의 감정을 일괄적으로 추출할 수 있도록 최적화되었습니다.

### [Core API Module]
- **`ChatStreamService`**: `analyzed-chat-topic`의 메시지를 소비하여 SSE(Server-Sent Events)를 통해 프런트엔드로 실시간 푸시합니다.
- **Storage Strategy**: CHAT 타입은 PostgreSQL에 영구 저장하고, DONATION/SUBSCRIPTION은 SSE 이벤트로만 전달하여 DB 부하를 관리합니다.

---

## 2. Frontend: Dashboard & Real-time Integration

### [Dashboard UI]
- `src/app/channels/[channelId]/page.tsx`: SSE 엔드포인트(`http://localhost:8081/api/v1/channels/{id}/subscribe`)를 구독합니다.
- **Dummy Support**: API 연동 전 UI 테스트를 위해 3초 단위 더미 데이터 생성 지원 로직이 포함되어 있습니다.

### [Channel Search]
- 유저가 자신의 채널 ID를 직접 입력하여 대시보드로 진입할 수 있는 검색 기능을 홈 화면에 추가하였습니다.

---

## 3. Future Extension: JNI & Rust

- **`NativeBridge`**: `System.loadLibrary("neul_native")`를 위한 인터페이스를 제공하며, 라이브러리 존재 여부에 따라 Java Native 또는 Pure Java 로직으로 동적 분기됩니다.
- **Docs Strategy**: `docs/testing_and_documentation_guide.md`에 정의된 지침에 따라 모든 네이티브 전환 과정은 벤치마킹 데이터와 함께 기록될 예정입니다.
