package com.neul.collector.controller;

import com.neul.collector.service.MockChatScheduler;
// Assuming ApiResponse is copied/available in this module as well. If not, returning a map or simple response for now.
// For the sake of simplicity and avoiding cross-module direct dependency unless set up, I'll use a local Map or standard ResponseEntity, but following the spec, I should be using an ApiResponse. I'll create it here or assume a shared library. Since there's no common library created yet, I'll return a localized ApiResponse.
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/broadcasts")
@RequiredArgsConstructor
public class BroadcastController {

    private final MockChatScheduler mockChatScheduler;

    @PostMapping
    public ApiResponse<Map<String, String>> startBroadcast() {
        // Generate a random roomId for testing or take it from request if needed.
        // For simplicity, generating a mock roomId
        String roomId = UUID.randomUUID().toString().substring(0, 8);
        
        mockChatScheduler.startBroadcast(roomId);
        
        return ApiResponse.success(Map.of("roomId", roomId, "status", "started"));
    }
    
    // Additional stopping endpoint just to clean up mock generation if needed
    @PostMapping("/{roomId}/stop")
    public ApiResponse<String> stopBroadcast(@PathVariable String roomId) {
        mockChatScheduler.stopBroadcast(roomId);
        return ApiResponse.success("Broadcast mock stopped for room: " + roomId);
    }

    // Localized ApiResponse to fulfill the spec since the core ApiResponse is in another module
    // and common module hasn't been extracted yet.
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ApiResponse<T> {
        private int status;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            return ApiResponse.<T>builder()
                    .status(200)
                    .message("Success")
                    .data(data)
                    .build();
        }
    }
}
