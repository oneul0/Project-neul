package com.neul.collector.controller;

import com.neul.collector.chzzk.ChzzkTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Chzzk OAuth인증 콜백 컨트롤러.
 * - 사용자가 치지직 로그인 및 권한 동의 후 리다이렉트되는 곳.
 * - 전달받은 code를 이용해 첫 Access Token을 발급받습니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chzzk")
@RequiredArgsConstructor
public class ChzzkAuthController {

    private final ChzzkTokenService tokenService;

    /**
     * OAuth 콜백 엔드포인트.
     * @param code 인증 코드
     * @param state 요청 시 보낸 상태값 (검증용)
     */
    @GetMapping("/callback")
    public Mono<ResponseEntity<Map<String, Object>>> callback(
            @RequestParam String code,
            @RequestParam String state) {
        
        log.info("[Chzzk] Callback received. code: {}, state: {}", 
                code.substring(0, Math.min(5, code.length())) + "...", state);

        return tokenService.fetchFirstToken(code, state)
                .map(token -> ResponseEntity.ok(Map.<String, Object>of(
                        "status", "success",
                        "message", "Chzzk Access Token issued successfully.",
                        "tokenPreview", token.substring(0, Math.min(10, token.length())) + "..."
                )))
                .onErrorResume(e -> {
                    log.error("[Chzzk] Token exchange failed: {}", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.<String, Object>of(
                                    "status", "failed",
                                    "error", e.getMessage()
                            )));
                });
    }
}
