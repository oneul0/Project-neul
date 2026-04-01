package com.neul.core_api.domain.chat.service;

import com.neul.core_api.domain.chat.dto.UserVodPreferenceProfile;
import com.neul.core_api.domain.chat.entity.UserVodActivity;
import com.neul.core_api.domain.chat.entity.UserVodLibraryEntry;
import com.neul.core_api.domain.chat.entity.VodHighlight;
import com.neul.core_api.domain.chat.repository.UserVodActivityRepository;
import com.neul.core_api.domain.chat.repository.UserVodLibraryRepository;
import com.neul.core_api.domain.chat.repository.VodHighlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserVodLibraryService {

    private final UserVodLibraryRepository userVodLibraryRepository;
    private final UserVodActivityRepository userVodActivityRepository;
    private final VodHighlightRepository vodHighlightRepository;

    public Flux<UserVodLibraryEntry> getLibrary(String ownerId) {
        return userVodLibraryRepository.findAllByOwnerIdOrderByUpdatedAtDesc(ownerId)
                .onErrorResume(error -> {
                    log.warn("[UserVodLibraryService] Failed to load library for ownerId={}, returning empty list", ownerId, error);
                    return Flux.empty();
                });
    }

    public Flux<UserVodActivity> getActivities(String ownerId, String videoNo) {
        return userVodActivityRepository.findAllByOwnerIdAndVideoNoOrderByCreatedAtDesc(ownerId, videoNo)
                .onErrorResume(error -> {
                    log.warn("[UserVodLibraryService] Failed to load activities for ownerId={}, videoNo={}, returning empty list", ownerId, videoNo, error);
                    return Flux.empty();
                });
    }

    public Mono<UserVodPreferenceProfile> getPreferenceProfile(String ownerId) {
        return userVodActivityRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId)
                .collectList()
                .flatMap(activities -> {
                    if (activities.isEmpty()) {
                        return Mono.just(emptyPreferenceProfile());
                    }

                    List<String> videoNos = activities.stream()
                            .map(UserVodActivity::getVideoNo)
                            .distinct()
                            .toList();

                    return Flux.fromIterable(videoNos)
                            .flatMap(vodHighlightRepository::findAllByVideoNoOrderByStartSecondsAsc)
                            .collectMap(VodHighlight::getId, highlight -> highlight)
                            .map(highlightMap -> buildPreferenceProfile(activities, highlightMap));
                })
                .onErrorResume(error -> {
                    log.warn("[UserVodLibraryService] Failed to build preference profile for ownerId={}, returning empty profile", ownerId, error);
                    return Mono.just(emptyPreferenceProfile());
                });
    }

    public Flux<VodHighlight> getPersonalizedHighlights(String ownerId, String videoNo) {
        Mono<List<VodHighlight>> highlightsMono =
                vodHighlightRepository.findAllByVideoNoOrderByStartSecondsAsc(videoNo).collectList();

        if (ownerId == null || ownerId.isBlank()) {
            return highlightsMono.flatMapMany(highlights -> Flux.fromIterable(rankHighlights(highlights, Map.of(), emptyPreferenceProfile())));
        }

        Mono<List<UserVodActivity>> activityMono =
                userVodActivityRepository.findAllByOwnerIdAndVideoNoOrderByCreatedAtDesc(ownerId, videoNo).collectList();

        return Mono.zip(highlightsMono, activityMono, getPreferenceProfile(ownerId))
                .flatMapMany(tuple -> {
                    List<VodHighlight> highlights = tuple.getT1();
                    List<UserVodActivity> activities = tuple.getT2();
                    UserVodPreferenceProfile profile = tuple.getT3();

                    Map<Long, String> latestActionByHighlight = new HashMap<>();
                    for (UserVodActivity activity : activities) {
                        if (activity.getHighlightId() == null || latestActionByHighlight.containsKey(activity.getHighlightId())) {
                            continue;
                        }
                        latestActionByHighlight.put(activity.getHighlightId(), normalizeActionType(activity.getActionType()));
                    }

                    return Flux.fromIterable(rankHighlights(highlights, latestActionByHighlight, profile));
                })
                .onErrorResume(error -> {
                    log.warn("[UserVodLibraryService] Failed to personalize highlights for ownerId={}, videoNo={}, returning default order", ownerId, videoNo, error);
                    return highlightsMono.flatMapMany(highlights ->
                            Flux.fromIterable(rankHighlights(highlights, Map.of(), emptyPreferenceProfile()))
                    );
                });
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
                        .build()))
                .onErrorResume(error -> {
                    log.warn("[UserVodLibraryService] Failed to touch library entry for ownerId={}, videoNo={}", ownerId, videoNo, error);
                    return Mono.empty();
                });
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
                        .build()))
                .onErrorResume(error -> {
                    log.warn("[UserVodLibraryService] Failed to mark analyzed for ownerId={}, videoNo={}", ownerId, videoNo, error);
                    return Mono.empty();
                });
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
                .build())
                .onErrorResume(error -> {
                    log.warn("[UserVodLibraryService] Failed to record activity ownerId={}, videoNo={}, highlightId={}, actionType={}",
                            ownerId, videoNo, highlightId, actionType, error);
                    return Mono.empty();
                });
    }

    private UserVodPreferenceProfile buildPreferenceProfile(
            List<UserVodActivity> activities,
            Map<Long, VodHighlight> highlightMap
    ) {
        Map<String, Double> categoryAffinity = new LinkedHashMap<>();
        Map<String, Double> reactionAffinity = new LinkedHashMap<>();

        for (UserVodActivity activity : activities) {
            if (activity.getHighlightId() == null) {
                continue;
            }

            VodHighlight highlight = highlightMap.get(activity.getHighlightId());
            if (highlight == null) {
                continue;
            }

            double weight = actionWeight(activity.getActionType());
            if (weight == 0) {
                continue;
            }

            if (highlight.getCategory() != null && !highlight.getCategory().isBlank()) {
                categoryAffinity.merge(highlight.getCategory(), weight, Double::sum);
            }

            if (highlight.getReactionLabel() != null && !highlight.getReactionLabel().isBlank()) {
                reactionAffinity.merge(highlight.getReactionLabel(), weight, Double::sum);
            }
        }

        return UserVodPreferenceProfile.builder()
                .topCategories(topKeys(categoryAffinity))
                .topReactionLabels(topKeys(reactionAffinity))
                .categoryAffinity(categoryAffinity)
                .reactionAffinity(reactionAffinity)
                .build();
    }

    private UserVodPreferenceProfile emptyPreferenceProfile() {
        return UserVodPreferenceProfile.builder()
                .topCategories(List.of())
                .topReactionLabels(List.of())
                .categoryAffinity(Map.of())
                .reactionAffinity(Map.of())
                .build();
    }

    private List<String> topKeys(Map<String, Double> affinity) {
        return affinity.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> entry.getValue() != null)
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private List<VodHighlight> rankHighlights(
            List<VodHighlight> highlights,
            Map<Long, String> latestActionByHighlight,
            UserVodPreferenceProfile profile
    ) {
        return highlights.stream()
                .filter(highlight -> highlight != null)
                .sorted((left, right) -> Double.compare(
                        personalizedScore(right, latestActionByHighlight, profile),
                        personalizedScore(left, latestActionByHighlight, profile)
                ))
                .toList();
    }

    private double personalizedScore(
            VodHighlight highlight,
            Map<Long, String> latestActionByHighlight,
            UserVodPreferenceProfile profile
    ) {
        if (highlight == null) {
            return Double.NEGATIVE_INFINITY;
        }

        Map<Long, String> safeActions = latestActionByHighlight != null ? latestActionByHighlight : Map.of();
        UserVodPreferenceProfile safeProfile = profile != null ? profile : emptyPreferenceProfile();

        String actionType = safeActions.get(highlight.getId());
        double actionBoost = switch (actionType) {
            case "PIN" -> 120.0d;
            case "GOOD" -> 48.0d;
            case "OPEN" -> 6.0d;
            case "BAD" -> -72.0d;
            default -> 0.0d;
        };

        String category = highlight.getCategory();
        String reactionLabel = highlight.getReactionLabel();

        double categoryBoost =
                category != null
                        ? safeProfile.getCategoryAffinity().getOrDefault(category, 0.0d) * 2.0d
                        : 0.0d;
        double reactionBoost =
                reactionLabel != null
                        ? safeProfile.getReactionAffinity().getOrDefault(reactionLabel, 0.0d) * 1.5d
                        : 0.0d;
        double preferenceBoost = categoryBoost + reactionBoost;

        double baseScore = safe(highlight.getHighlightScore());
        double editorialBoost =
                (safe(highlight.getEditabilityScore()) * 1.8d) +
                (safe(highlight.getTransitionScore()) * 1.3d) +
                (safe(highlight.getIntensityScore()) * 0.9d);

        return actionBoost + preferenceBoost + editorialBoost + baseScore;
    }

    private String normalizeActionType(String actionType) {
        if (actionType == null) {
            return "";
        }

        return switch (actionType) {
            case "SAVE" -> "GOOD";
            case "SKIP" -> "BAD";
            default -> actionType;
        };
    }

    private double safe(Double value) {
        return value != null ? value : 0.0d;
    }

    private double actionWeight(String actionType) {
        if (actionType == null) {
            return 0.0d;
        }

        return switch (normalizeActionType(actionType)) {
            case "PIN" -> 4.0d;
            case "GOOD" -> 3.0d;
            case "OPEN" -> 1.0d;
            case "BAD" -> -3.0d;
            default -> 0.0d;
        };
    }
}
