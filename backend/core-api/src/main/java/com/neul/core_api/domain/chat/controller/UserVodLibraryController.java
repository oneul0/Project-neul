package com.neul.core_api.domain.chat.controller;

import com.neul.core_api.domain.chat.entity.UserVodActivity;
import com.neul.core_api.domain.chat.entity.UserVodLibraryEntry;
import com.neul.core_api.domain.chat.service.OwnerIdentityResolver;
import com.neul.core_api.domain.chat.service.UserVodLibraryService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class UserVodLibraryController {

    private final OwnerIdentityResolver ownerIdentityResolver;
    private final UserVodLibraryService userVodLibraryService;

    @GetMapping("/vod-library")
    public Flux<UserVodLibraryEntry> getVodLibrary(ServerHttpRequest request) {
        String ownerId = requireOwnerId(request);
        return userVodLibraryService.getLibrary(ownerId);
    }

    @GetMapping("/vod/{videoNo}/activity")
    public Flux<UserVodActivity> getVodActivity(
            @PathVariable String videoNo,
            ServerHttpRequest request
    ) {
        String ownerId = requireOwnerId(request);
        return userVodLibraryService.getActivities(ownerId, videoNo);
    }

    @PostMapping("/vod/{videoNo}/activity")
    public Mono<UserVodActivity> recordActivity(
            @PathVariable String videoNo,
            @RequestBody UserVodActivityRequest requestBody,
            ServerHttpRequest request
    ) {
        String ownerId = requireOwnerId(request);
        return userVodLibraryService.recordActivity(
                ownerId,
                videoNo,
                requestBody.getHighlightId(),
                requestBody.getActionType()
        );
    }

    private String requireOwnerId(ServerHttpRequest request) {
        String ownerId = ownerIdentityResolver.resolveOwnerId(request);
        if (ownerId == null || ownerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CHZZK login is required.");
        }
        return ownerId;
    }

    @Getter
    public static class UserVodActivityRequest {
        private Long highlightId;
        private String actionType;
    }
}
