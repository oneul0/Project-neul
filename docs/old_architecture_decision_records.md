# Architecture Decision Records — 늘(Neul) 추가 의사결정

이 문서는 `01_ADR.md`에 이어, 개발 진행 중 발생한 심층 기술 의사결정을 별도로 기록합니다.
각 항목은 **Context → Decision → Consequences** 3단계 구조를 따릅니다.

---

## [ADR-005] Chat Optimizer 엔진 교체 가능 구조 설계: 패턴 비교 후 Port & Adapter 채택

- **날짜:** 2026-03-04
- **결정 안건:** Gemini API 호출 전 채팅 데이터 최적화(필터링+압축) 레이어의 구조 설계. 현재는 Java로 구현하되, 추후 Rust 모듈을 JNI(Java Native Interface)를 통해 교체할 수 있는 구조가 요구됨.

---

### Context (문제 상황)

`neul-analyzer`는 Kafka로부터 최대 50건의 채팅 배치를 수신하여 Gemini API로 전달한다. 이때 두 가지 비용 문제가 발생한다.

1. **API 토큰 비용**: 동일 내용의 반복 메시지(`"ㅋㅋㅋ"` × 30건)가 그대로 전달되면 토큰이 낭비됨.
2. **JVM 연산 비용**: 배치가 대규모화될 경우, JVM의 GC 압박 및 텍스트 처리 연산 비용이 무시할 수 없는 수준에 이를 것으로 예측됨.

장기적으로 Rust의 메모리 안전성과 zero-cost abstraction 특성이 고빈도 텍스트 처리에 유리하다고 판단, Rust 네이티브 모듈을 JNI로 연동하는 방향을 탐색 중이다. 단, 현재 Rust는 문법을 익히고 있는 학습 단계로, 즉시 적용이 아닌 **교체 가능한 구조를 선제적으로 설계**하는 것이 이번 결정의 핵심이다.

이를 위해 아래 4가지 설계 패턴을 비교 분석하였다.

---

### 패턴 비교 분석

#### 후보 1: Strategy Pattern (GoF)
```
ChatAnalysisProcessor
  → ChatOptimizationStrategy (interface)
      ├── JavaOptimizationStrategy
      └── RustOptimizationStrategy
```

| 항목 | 평가 |
|------|------|
```markdown
| 구조 단순성 | ✅ GoF 표준 패턴으로 구조가 직관적이며 구현이 용이함 |
```
| 교체 용이성 | ✅ 런타임 전략 교체 가능 |
| JNI 의미 표현 | ⚠️ Rust 네이티브 코드가 단순 "전략 변형"으로만 표현됨. 외부 시스템 경계임을 코드 구조상 명시하지 않음 |
| Spring 통합 | ⚠️ `@ConditionalOnProperty` 활용 가능하나, 런타임 교체를 위한 Context 클래스 관리가 추가로 필요 |

#### 후보 2: Template Method Pattern
```
AbstractChatOptimizer (abstract class)
  ├── filter() — abstract
  ├── compress() — abstract
  └── optimize() — final (template method)
```

| 항목 | 평가 |
|------|------|
| 파이프라인 구조화 | ✅ filter → compress 단계를 명확히 선언 |
| 교체 용이성 | ❌ 상속 기반이므로 Rust JNI 교체 시 클래스 계층 전체를 변경해야 함 |
| 테스트 용이성 | ❌ `abstract class` 의존성으로 인해 Mocking이 Strategy 대비 어려움 |
| 결론 | JNI 교체 구조에 부적합 |

#### 후보 3: Chain of Responsibility Pattern
```
FilterHandler → CompressHandler → [미래: RustHandler] → ...
```

| 항목 | 평가 |
|------|------|
| 파이프라인 확장성 | ✅ 핸들러 추가/제거 자유로움 |
```markdown
| JNI 교체 단위 | ❌ 엔진 전체의 네이티브 전환보다 개별 기능 단위의 파편화된 교체가 발생할 수 있어, 시스템 경계가 불명확해질 위험이 있음 |
```
| 복잡도 | ❌ 현재 요구사항(filter + compress 2단계)에 비해 과도한 구조 |

#### 후보 4: Port & Adapter Pattern (Hexagonal Architecture) ← **최종 채택**
```
ChatAnalysisProcessor (Domain)
  → ChatOptimizer (Port / Interface)
      ├── JavaChatOptimizer  (Adapter, engine=java)
      └── RustChatOptimizer  (Adapter, engine=rust) ← JNI 교체 지점
```

| 항목 | 평가 |
|------|------|
```markdown
| JNI 의미 표현 | ✅ JNI 호출은 단순한 로직의 차이를 넘어 Java 런타임 외부(Native)와의 통신을 수반함. Port & Adapter는 이를 '외부 인프라'로 간주하여, 도메인 로직과 물리적으로 분리된 시스템 경계를 코드 구조상에서 명확히 표현함. Hexagonal 관점에서 JNI는 외부 시스템과의 접점이므로 의미적으로 가장 정확한 모델링임 |
```
| 교체 용이성 | ✅ `application.yaml`의 `app.optimizer.engine` 값 하나로 전환. 도메인(`ChatAnalysisProcessor`) 코드 변경 없음 |
| 테스트 용이성 | ✅ `ChatOptimizer` 인터페이스 Mocking으로 도메인 로직과 완전히 분리하여 테스트 가능 |
| Fallback 안전성 | ✅ `RustChatOptimizer`의 네이티브 라이브러리 로드 실패 시 `JavaChatOptimizer`로 자동 위임하는 Fallback 로직 내장 가능 |
| 복잡도 | ✅ Strategy와 구현 복잡도 동일 수준. 단, 아키텍처 의도가 더 명확함 |

---

### Decision (의사결정)

**Port & Adapter 패턴**을 채택한다.

Strategy Pattern과 구현 결과는 사실상 동일하나, Port & Adapter가 선택된 결정적 이유는 다음과 같다.

> Rust/JNI는 단순한 "알고리즘 변형(Strategy)"이 아니라, **Java 런타임 외부 경계(External Native Boundary)**를 넘는 행위다. 이를 Adapter로 명명함으로써, 추후 팀 합류자 혹은 Rust 모듈 개발자가 `RustChatOptimizer`의 역할을 코드 구조만 보고 즉시 파악할 수 있다.

구체적 설계:

```
optimization/
├── ChatOptimizer.java          ← Port (인터페이스)
├── CompressedChat.java         ← 압축 대표 메시지 DTO
├── OptimizedBatch.java         ← 최적화 결과 DTO (필터 통계 포함)
├── ChatOptimizerConfig.java    ← @ConditionalOnProperty로 어댑터 선택
├── java/
│   └── JavaChatOptimizer.java  ← Java Adapter (현재 운용)
└── jni/
    └── RustChatOptimizer.java  ← Rust JNI Adapter (미래 교체 대상, 현재 Fallback 포함)
```

전환 방법: `application.yaml`에서 `app.optimizer.engine: java` → `rust` 변경만으로 완료.

---

### Consequences (결과 및 영향)

**긍정적 결과:**

| 항목 | 효과 |
|------|------|
| Gemini API 토큰 절감 | 스팸(이모지 전용, 1~2자) 필터링 + 동일 내용 중복 압축으로 배치당 전달 토큰 수 최소화 |
| 도메인 불변성 | `ChatAnalysisProcessor`는 `ChatOptimizer` 인터페이스만 알며, 엔진이 Java든 Rust든 코드 변경 없음 |
| 점진적 전환 | Rust 학습이 완료되고 네이티브 모듈이 준비되면, Fallback 없이 `engine=rust`로 전환 가능 |
| 관심사 분리 | 필터/압축 로직이 `JavaChatOptimizer` 내부로 캡슐화되어, Kafka 리스너(`ChatAnalysisProcessor`)가 최적화 세부 구현에 무관해짐 |

**트레이드오프:**

| 항목 | 내용 |
|------|------|
| JNI 복잡도 | Rust 모듈 개발 시 JNI 바인딩(`jni` crate), 빌드 파이프라인(`cargo build`), 라이브러리 배포(`.dll`/`.so`) 관리가 필요함 |
| 초기 보일러플레이트 | Strategy 패턴 대비 `ChatOptimizerConfig`, `jni/` 패키지 등 파일 수가 소폭 증가 |
| Rust 적용 시점 미확정 | 현재 Rust 문법 학습 단계로, 네이티브 모듈의 실제 적용 시점은 미정. `RustChatOptimizer`는 현시점 스텁(Stub) 상태 유지 |

---

### Implementation Notes

#### `System.loadLibrary()`는 왜 static 블록 안에 있는가?

`RustChatOptimizer`의 아래 코드에 대한 설명.

```java
static {
    try {
        System.loadLibrary("neul_optimizer");
        nativeLibraryLoaded = true;
    } catch (UnsatisfiedLinkError e) {
        nativeLibraryLoaded = false; // → JavaChatOptimizer로 fallback
    }
}
```

**이유: 네이티브 라이브러리는 프로세스 전체에서 딱 한 번만 메모리에 올라오면 된다.**

`static` 블록은 **클래스가 JVM에 처음 로드될 때 딱 한 번만 실행**된다. 생성자 안에 넣으면 객체가 생성될 때마다 실행되어 "이미 로드된 라이브러리를 다시 로드" 시도가 반복된다.

```java
// ❌ 잘못된 방법 — 객체 생성 시마다 로드 시도
public RustChatOptimizer() {
    System.loadLibrary("neul_optimizer"); // 두 번째 호출부터 에러 또는 경고
}

// ✅ 올바른 방법 — 클래스 로드 시 1회만
static {
    System.loadLibrary("neul_optimizer"); // JVM이 클래스를 처음 참조할 때 딱 1번
}
```

**실행 시점 순서:**

```
1. Spring이 ChatOptimizerConfig 읽음 → RustChatOptimizer Bean 생성 시도
2. JVM이 RustChatOptimizer.class를 처음 참조
3. static 블록 실행
   → OS가 neul_optimizer.dll (Windows) / libneul_optimizer.so (Linux) 탐색
   → 성공 시: nativeLibraryLoaded = true
   → 실패 시: UnsatisfiedLinkError 캐치 → nativeLibraryLoaded = false
4. 이후 new RustChatOptimizer() 호출 (static 블록은 다시 실행되지 않음)
5. optimize() 호출 시 nativeLibraryLoaded 값에 따라 native 또는 Java fallback 분기
```

**현재 상태:** Rust 모듈 미완성으로 `.dll`/`.so` 파일이 없으므로 항상 `UnsatisfiedLinkError`가 발생하여 `JavaChatOptimizer`로 자동 위임된다. Rust 모듈 완성 후 빌드 산출물을 JVM 라이브러리 경로에 배치하면 별도 코드 수정 없이 네이티브 경로로 전환된다.

---

*작성: 2026-03-04 | 관련 파일: `neul-analyzer/optimization/`*
