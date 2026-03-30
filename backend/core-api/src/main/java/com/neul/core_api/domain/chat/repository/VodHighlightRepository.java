package com.neul.core_api.domain.chat.repository;

import com.neul.core_api.domain.chat.entity.VodHighlight;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/**
 * VOD 하이라이트 데이터 접근을 위한 Repository.
 */
public interface VodHighlightRepository extends ReactiveCrudRepository<VodHighlight, Long> {
    Flux<VodHighlight> findAllByVideoNoOrderByStartSecondsAsc(String videoNo);
}
