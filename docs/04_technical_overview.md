# Technical Overview & Component Changes

이 문서는 최근 리팩토링 및 기능 개발 과정에서 변경된 주요 컴포넌트의 기술적 상세 내용을 기록합니다.

---

## 1. Backend: Micro-batch Architecture

### [Collector Module]
- **`NidChatCollector`**: 치지직 채팅 채널 ID 및 엑세스 토큰을 조회한 후, `wss://kr-ss1.chat.naver.com/chat`에 연결하여 실시간 데이터를 수집합니다.
### [Collector Module]
- **`NidChatCollector`**: 치지직 채팅 채널 ID 및 엑세스 토큰을 조회한 후, `wss://kr-ss1.chat.naver.com/chat`에 연결하여 실시간 데이터를 수집합니다.
- **Batching Scheme**: `window(Duration.ofSeconds(2))`로 묶어 **2초 단위**의 고속 마이크로배칭을 수행하여 실시간성을 극대화했습니다.

### [Analyzer Module]
- **`ChatAnalysisProcessor`**: Kafka에서 전달받은 JSON 리스트를 Java 리스트로 역직렬화한 후, 일괄 분석을 수행합니다.
- **7-Emotion Analysis**: Ollama를 통해 기쁨, 희망, 슬픔, 분노 등 총 7가지 상세 감정을 분석하도록 프롬프트가 고도화되었습니다.

### [Core API Module]
- **`ChatStreamService`**: `analyzed-chat-topic`의 메시지를 소비하여 SSE(Server-Sent Events)를 통해 프런트엔드로 실시간 푸시합니다.
- **Highlight Engine**: 0.8 이상의 감정 스파이크를 감지하여 타임라인 이벤트를 발생시키고, 치지직 실시간 썸네일 URL을 함께 제공합니다.

---

## 2. Frontend: Dashboard & Real-time Integration

### [Dashboard UI]
- `src/app/channels/[channelId]/page.tsx`: SSE 엔드포인트(`http://localhost:8081/api/v1/channels/{id}/subscribe`)를 구독합니다.

### [Channel Search]
- 유저가 자신의 채널 ID를 직접 입력하여 대시보드로 진입할 수 있는 검색 기능을 홈 화면에 추가하였습니다.

---

## 3. Future Extension: JNI & Rust

- **`NativeBridge`**: `System.loadLibrary("neul_native")`를 위한 인터페이스를 제공하며, 라이브러리 존재 여부에 따라 Java Native 또는 Pure Java 로직으로 동적 분기됩니다.
- **Docs Strategy**: `docs/testing_and_documentation_guide.md`에 정의된 지침에 따라 모든 네이티브 전환 과정은 벤치마킹 데이터와 함께 기록될 예정입니다.
