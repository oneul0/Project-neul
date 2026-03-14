package com.neul.core_api.domain.chat.repository;

import com.neul.core_api.domain.chat.entity.HighlightRecord;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HighlightRepository extends ReactiveCrudRepository<HighlightRecord, Long> {
}
