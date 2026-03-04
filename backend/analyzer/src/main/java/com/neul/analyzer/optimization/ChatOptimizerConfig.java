package com.neul.analyzer.optimization;

import com.neul.analyzer.optimization.java.JavaChatOptimizer;
import com.neul.analyzer.optimization.jni.RustChatOptimizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ChatOptimizer} 구현체를 {@code app.optimizer.engine} 값에 따라 선택하는 Spring 설정.
 *
 * <ul>
 * <li>{@code java} (기본값): {@link JavaChatOptimizer} 등록</li>
 * <li>{@code rust}: {@link RustChatOptimizer} 등록 (네이티브 라이브러리 없을 시 내부적으로 Java
 * Fallback 동작)</li>
 * </ul>
 *
 * <p>
 * 전환 방법: {@code application.yaml}에서 아래 값만 변경
 * 
 * <pre>
 * app:
 *   optimizer:
 *     engine: rust   # 'java' → 'rust' 로 변경
 * </pre>
 */
@Configuration
public class ChatOptimizerConfig {

    @Bean
    @ConditionalOnProperty(name = "app.optimizer.engine", havingValue = "java", matchIfMissing = true // engine 미설정 시
                                                                                                      // Java가 기본
    )
    public ChatOptimizer javaChatOptimizer() {
        return new JavaChatOptimizer();
    }

    @Bean
    @ConditionalOnProperty(name = "app.optimizer.engine", havingValue = "rust")
    public ChatOptimizer rustChatOptimizer() {
        // Rust 네이티브 로드 실패 시 JavaChatOptimizer로 자동 위임
        return new RustChatOptimizer(new JavaChatOptimizer());
    }
}
