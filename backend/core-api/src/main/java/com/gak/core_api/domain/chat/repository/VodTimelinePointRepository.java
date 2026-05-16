package com.gak.core_api.domain.chat.repository;

import com.gak.core_api.domain.chat.entity.VodTimelinePointEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface VodTimelinePointRepository extends ReactiveCrudRepository<VodTimelinePointEntity, Long> {
    Flux<VodTimelinePointEntity> findAllByVideoNoOrderByStartSecondsAsc(String videoNo);

    Mono<Integer> deleteAllByVideoNo(String videoNo);
}
