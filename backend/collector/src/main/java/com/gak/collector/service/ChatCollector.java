package com.gak.collector.service;

import reactor.core.publisher.Mono;

/**
 * 채팅 수집기 인터페이스.
 * - 실제 치지직 소켓 수집기(NidChatCollector)의 기본 규격.
 */
public interface ChatCollector {
    
    /**
     * 특정 채널(방)의 채팅 수집을 시작합니다.
     */
    Mono<Void> subscribe(String channelId);

    /**
     * 특정 채널(방)의 채팅 수집을 중단합니다.
     */
    Mono<Void> unsubscribe(String channelId);

    /**
     * 현재 수집 중인지 여부를 확인합니다.
     */
    boolean isSubscribed(String channelId);
}
