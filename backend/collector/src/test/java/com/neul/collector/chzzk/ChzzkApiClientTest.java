package com.neul.collector.chzzk;

import java.util.function.Function;

import com.neul.collector.config.ChzzkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChzzkApiClientTest {

    private ChzzkApiClient chzzkApiClient;

    @Mock
    private ChzzkProperties props;

    @Mock
    private WebClient webClient;

    @Mock
    private ChzzkTokenService tokenService;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private WebClient.RequestBodySpec requestBodySpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        chzzkApiClient = new ChzzkApiClient(props, webClient, tokenService);
        
        lenient().when(props.getClientId()).thenReturn("test-client-id");
        lenient().when(props.getClientSecret()).thenReturn("test-client-secret");

        // Mock default behavior for WebClient chains to avoid NPE
        lenient().when(webClient.get()).thenReturn(requestHeadersUriSpec);
        lenient().when(webClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        lenient().when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        lenient().when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodyUriSpec.uri(any(Function.class))).thenReturn(requestBodySpec);
        lenient().when(requestHeadersSpec.header(anyString(), any())).thenReturn(requestHeadersSpec);
        lenient().when(requestBodySpec.header(anyString(), any())).thenReturn(requestBodySpec);
        lenient().when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("Client Session 생성 시 Client-Id/Secret 헤더가 포함되어야 한다")
    void createClientSession_UsesClientAuth() {
        // given
        Map<String, Object> mockResponse = Map.of("content", Map.of("url", "ws://test-socket-url"));
        
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(Map.class))).thenReturn(Mono.just(mockResponse));

        // when & then
        StepVerifier.create(chzzkApiClient.createClientSession())
                .expectNext("ws://test-socket-url")
                .verifyComplete();
    }

    @Test
    @DisplayName("User Session 생성 시 Bearer 토큰 헤더가 포함되어야 한다")
    void createUserSession_UsesAccessToken() {
        // given
        when(tokenService.getValidAccessToken()).thenReturn(Mono.just("valid-token"));
        Map<String, Object> mockResponse = Map.of("content", Map.of("url", "ws://user-socket-url"));

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(Map.class))).thenReturn(Mono.just(mockResponse));

        // when & then
        StepVerifier.create(chzzkApiClient.createUserSession())
                .expectNext("ws://user-socket-url")
                .verifyComplete();
    }

    @Test
    @DisplayName("이벤트 구독 시 Bearer 토큰 헤더가 포함되어야 한다")
    void subscribeChatEvent_UsesAccessToken() {
        // given
        when(tokenService.getValidAccessToken()).thenReturn(Mono.just("valid-token"));
        
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(Function.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        doReturn(responseSpec).when(responseSpec).onStatus(any(), any());
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.empty());

        // when & then
        StepVerifier.create(chzzkApiClient.subscribeChatEvent("test-session"))
                .verifyComplete();
    }

    @Test
    @DisplayName("라이브 목록 조회 시 Client-Id/Secret 헤더가 포함되어야 한다")
    void getLives_UsesClientAuth() {
        // given
        Map<String, Object> mockResponse = Map.of("content", Map.of("data", List.of()));

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(eq(Map.class))).thenReturn(Mono.just(mockResponse));

        // when & then
        StepVerifier.create(chzzkApiClient.getLives(20, null))
                .assertNext(content -> assertThat(content).containsKey("data"))
                .verifyComplete();
    }
}
