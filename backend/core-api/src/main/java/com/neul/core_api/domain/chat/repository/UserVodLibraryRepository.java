package com.neul.core_api.domain.chat.repository;

import com.neul.core_api.domain.chat.entity.UserVodLibraryEntry;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserVodLibraryRepository extends ReactiveCrudRepository<UserVodLibraryEntry, Long> {
    Mono<UserVodLibraryEntry> findByOwnerIdAndVideoNo(String ownerId, String videoNo);
    Flux<UserVodLibraryEntry> findAllByOwnerIdOrderByUpdatedAtDesc(String ownerId);
}
