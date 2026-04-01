package com.neul.core_api.domain.chat.repository;

import com.neul.core_api.domain.chat.entity.UserVodActivity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface UserVodActivityRepository extends ReactiveCrudRepository<UserVodActivity, Long> {
    Flux<UserVodActivity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
