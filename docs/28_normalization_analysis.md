# 28. 정규화 분석

> 구현 기준: 2026-05-17  
> 스키마 소스: `backend/core-api/src/main/resources/db/migration/V1~V7__*.sql`

---

## 요약

| 테이블 | 1NF | 2NF | 3NF | 비고 |
|--------|-----|-----|-----|------|
| `analyzed_chats` | ✅ | ✅ | ✅ | — |
| `highlight_records` | ✅ | ✅ | ✅ | — |
| `vod_highlights` | ✅ | ✅ | ✅ | 계산 컬럼은 의도된 비정규화 |
| `vod_timeline_points` | ✅ | ✅ | ✅ | `activity_score`는 파생 필드 |
| `user_vod_library` | ✅ | ✅ | ✅ | — |
| `user_vod_activity` | ✅ | ✅ | ✅ | — |

전체 스키마는 3NF를 충족하며, 일부 파생 컬럼은 성능과 조회 단순화를 위해 의도적으로 비정규화했다.

---

## 각 테이블 분석

### `analyzed_chats`

**1NF**: 모든 컬럼은 원자적 단일 값. 반복 그룹 없음.  
**2NF**: PK는 `id`(단일 서로게이트). 부분 함수 종속성 없음.  
**3NF**: `emotion_type → emotion_score`처럼 보이지만, 동일 메시지에도 모델 출력에 따라 점수가 달라지므로 이행 종속 아님.

결론: **완전한 3NF**.

---

### `highlight_records`

**1NF**: 원자적 값. `top_message`, `live_image_url`은 단일 텍스트.  
**2NF**: 단일 PK.  
**3NF**: `room_id`는 채널 식별자이나, 별도 Channel 테이블 없음. `room_id`로부터 채널 메타정보를 파생할 수 없어 이행 종속 없음.

결론: **3NF**.

---

### `vod_highlights` — 의도된 비정규화

#### 정규화 충족 분석

**1NF**: 모든 컬럼 원자적. `embedding`은 768차원 벡터이나 단일 컬럼으로 저장 (pgvector 타입).  
**2NF**: PK는 `id` (단일 서로게이트). 부분 종속 없음.  
**3NF**: 이행 종속 후보 검토:

| 의존 관계 | 판단 |
|-----------|------|
| `intensity_score → totalScore` | `total_score`는 DB에 없음. 서비스에서 계산 |
| `laugh_ratio + hype_ratio + ... → emotion_dominance` | **파생 관계 존재** |
| `scene_label ↔ category, reaction_label` | 논리적 연관이지만 독립 저장 |

#### `emotion_dominance`의 파생 관계

`emotion_dominance`는 `laugh_ratio`, `hype_ratio`, `surprise_ratio`, `tension_ratio`의 최댓값으로 결정된다 (`VodHighlightAnalyzer.resolveEmotionDominance()`).

이를 3NF 위반으로 볼 수 있지만 의도적으로 비정규화를 선택한 이유:
1. **RAG 임베딩 소스 단순화**: `embedding_text`를 생성할 때 `emotion_dominance`를 직접 참조한다. 매번 재계산하면 임베딩 생성 경로가 복잡해진다.
2. **LLM 리뷰 직전 결정**: analyzer에서 LLM 리뷰 직후 `emotion_dominance`가 확정되므로 DB 저장 시점에 재계산이 불필요하다.
3. **조회 성능**: `embedding_text` 조회 시 비율 4개를 읽어 계산하는 것보다 단일 컬럼을 읽는 것이 빠르다.

#### `embedding_text`의 파생 관계

`embedding_text`는 다른 비율 컬럼들로부터 생성된다 (`HighlightEmbeddingService.buildEmbeddingText()`). 3NF 관점에서 이행 종속이지만 의도된 선택:

- 임베딩 모델이 변경될 경우 `embedding_text` 재생성이 필요하므로 소스 텍스트를 저장한다 (감사/재현성).
- pgvector의 `embedding` 컬럼이 변경될 경우 소스를 추적할 수 있다.

#### 결론

`emotion_dominance`와 `embedding_text`는 3NF 위반이나 **성능·감사·단순성을 위한 의도된 비정규화**다.

---

### `vod_timeline_points`

**`activity_score`** 파생 관계:

`activity_score = messages×1.2 + users×2.1 + burstSignal + variety×8 + coverage×6`

이 값은 `message_count`, `participant_count` 등에서 파생되는 이행 종속이다. 선택 이유:
- timeline 조회 시 매번 재계산하면 N개 포인트×O(계산) 비용.
- 타임라인은 쓰기 1회, 읽기 多 패턴 → 저장 시점 계산이 경제적.

결론: `activity_score`는 **조회 최적화를 위한 의도된 비정규화**.

---

### `user_vod_library`

VOD 메타데이터(title, duration 등)를 갖지 않는다. `video_no`만 저장하고 실제 메타데이터는 Chzzk API에서 on-demand 조회한다.

이유: VOD 메타데이터는 Chzzk가 권위 소스이고, 프로젝트가 변경 감지를 할 수 없다. 로컬 복사본을 유지하면 staleness 문제가 생긴다.

결론: **3NF + 합리적 의존성 미포함**.

---

### `user_vod_activity`

`action_type`의 정규화 여부:

현재 `OPEN/GOOD/BAD/PIN/SAVE/SKIP` 등 문자열로 저장. 별도 action_type 코드 테이블이 없다.

코드 테이블 미생성 이유:
- 타입이 6개로 안정적이고 애플리케이션 레이어(`normalizeActionType()`)에서 이미 정규화(`SAVE→GOOD`, `SKIP→BAD`).
- 조인 비용 없이 직접 읽을 수 있다.
- 타입 추가는 코드 변경이 필요해 DB 레벨 참조 무결성보다 코드 레벨 제어가 현실적.

결론: 의도적으로 코드 테이블을 두지 않은 설계. 애플리케이션이 정규화를 담당.

---

## FK 제약 미사용의 이유

모든 테이블은 논리적 참조 관계를 갖지만 DB 레벨 FK 제약을 선언하지 않았다.

**이유: R2DBC 드라이버**  
Spring Data R2DBC는 참조 무결성 위반이 발생할 경우 DB 드라이버가 리액티브 스트림 에러로 변환하는 경로가 표준화되어 있지 않다. FK 위반 시 Mono/Flux에서 예외 처리가 불투명해질 수 있다.

**대안**: 애플리케이션 레이어에서 보장.
- `VodController.doTriggerAnalysis()`는 분석 재실행 전 기존 `vod_highlights`와 `vod_timeline_points`를 명시적으로 삭제한다.
- `UserVodLibraryService`는 `touchVideo()`에서 upsert 패턴을 사용해 항상 라이브러리 엔트리가 존재하도록 보장한다.

---

## 정규화 적용/미적용 결정 요약

| 결정 | 대상 | 선택 | 이유 |
|------|------|------|------|
| 비정규화 | `emotion_dominance` | 비율 컬럼에서 파생되나 저장 | RAG 임베딩 단순화 |
| 비정규화 | `embedding_text` | 비율 컬럼에서 파생되나 저장 | 임베딩 소스 감사·재현 |
| 비정규화 | `activity_score` | 집계 컬럼에서 파생되나 저장 | 읽기 최적화 |
| 코드 테이블 미사용 | `action_type` | 문자열 직접 저장 | 조인 비용 제거, 앱 레이어 정규화 |
| FK 제약 미사용 | 전체 | 논리적 참조만 | R2DBC 에러 처리 복잡성 |
| Video 테이블 미생성 | `video_no` | 외부 API 의존 | Chzzk가 권위 소스, staleness 방지 |
