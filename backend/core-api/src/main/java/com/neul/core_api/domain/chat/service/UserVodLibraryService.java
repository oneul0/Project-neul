package com.neul.core_api.domain.chat.service;

import com.neul.core_api.domain.chat.entity.UserVodActivity;
import com.neul.core_api.domain.chat.entity.UserVodLibraryEntry;
import com.neul.core_api.domain.chat.repository.UserVodActivityRepository;
import com.neul.core_api.domain.chat.repository.UserVodLibraryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserVodLibraryService {

    private final UserVodLibraryRepository userVodLibraryRepository;
    private final UserVodActivityRepository userVodActivityRepository;

    public Flux<UserVodLibraryEntry> getLibrary(String ownerId) {
        return userVodLibraryRepository.findAllByOwnerIdOrderByUpdatedAtDesc(ownerId);
    }

    public Flux<UserVodActivity> getActivities(String ownerId, String videoNo) {
        return userVodActivityRepository.findAllByOwnerIdAndVideoNoOrderByCreatedAtDesc(ownerId, videoNo);
    }

    public Mono<UserVodLibraryEntry> touchVideo(String ownerId, String videoNo, String status) {
        LocalDateTime now = LocalDateTime.now();
        return userVodLibraryRepository.findByOwnerIdAndVideoNo(ownerId, videoNo)
                .defaultIfEmpty(UserVodLibraryEntry.builder()
                        .ownerId(ownerId)
                        .videoNo(videoNo)
                        .createdAt(now)
                        .build())
                .flatMap(existing -> userVodLibraryRepository.save(UserVodLibraryEntry.builder()
                        .id(existing.getId())
                        .ownerId(ownerId)
                        .videoNo(videoNo)
                        .status(status != null ? status : existing.getStatus())
                        .lastViewedAt(now)
                        .lastAnalyzedAt(existing.getLastAnalyzedAt())
                        .createdAt(existing.getCreatedAt() != null ? existing.getCreatedAt() : now)
                        .updatedAt(now)
                        .build()));
    }

    public Mono<UserVodLibraryEntry> markAnalyzed(String ownerId, String videoNo, String status) {
        LocalDateTime now = LocalDateTime.now();
        return userVodLibraryRepository.findByOwnerIdAndVideoNo(ownerId, videoNo)
                .defaultIfEmpty(UserVodLibraryEntry.builder()
                        .ownerId(ownerId)
                        .videoNo(videoNo)
                        .createdAt(now)
                        .build())
                .flatMap(existing -> userVodLibraryRepository.save(UserVodLibraryEntry.builder()
                        .id(existing.getId())
                        .ownerId(ownerId)
                        .videoNo(videoNo)
                        .status(status)
                        .lastViewedAt(existing.getLastViewedAt() != null ? existing.getLastViewedAt() : now)
                        .lastAnalyzedAt(now)
                        .createdAt(existing.getCreatedAt() != null ? existing.getCreatedAt() : now)
                        .updatedAt(now)
                        .build()));
    }

    public Mono<UserVodActivity> recordActivity(
            String ownerId,
            String videoNo,
            Long highlightId,
            String actionType
    ) {
        return userVodActivityRepository.save(UserVodActivity.builder()
                .ownerId(ownerId)
                .videoNo(videoNo)
                .highlightId(highlightId)
                .actionType(actionType)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
