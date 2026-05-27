package com.gak.core_api.domain.chat.service.dto;

/**
 * VOD 분석 트리거 결과 도메인 타입.
 * VodAnalysisOrchestrator → VodController 사이의 계층 계약.
 *
 * <p>interface + records 패턴 (Java 17 호환, sealed 없이 instanceof 분기).
 */
public interface TriggerResult {

    /** 슬롯 획득 및 파이프라인 시작 성공. */
    record Accepted(String message) implements TriggerResult {}

    /** 해당 사용자가 이미 분석 중 → HTTP 429. */
    record RejectedUser() implements TriggerResult {}

    /** 시스템 전체 슬롯 소진 → HTTP 503. */
    record RejectedGlobal() implements TriggerResult {}

    /** 파이프라인 실행 중 오류 → HTTP 500. */
    record Failed(String reason) implements TriggerResult {}
}
