package com.gak.analyzer.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public NewTopic analyzedChatTopic() {
        return TopicBuilder.name("analyzed-chat-topic")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic analyzerDlqTopic() {
        return TopicBuilder.name("analyzer-dlq-topic")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic vodRawChatTopic() {
        return TopicBuilder.name("vod-raw-chat-topic")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic vodCrawlCompleteTopic() {
        return TopicBuilder.name("vod-crawl-complete-topic")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic vodAnalyzedTopic() {
        return TopicBuilder.name("vod-analyzed-topic")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic vodWindowSummaryTopic() {
        return TopicBuilder.name("vod-window-summary-topic")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic vodAnalysisCompleteTopic() {
        return TopicBuilder.name("vod-analysis-complete-topic")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic vodAnalysisFailedTopic() {
        return TopicBuilder.name("vod-analysis-failed-topic")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic v2RawChatTopic() {
        return TopicBuilder.name("v2-raw-chat")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic v2SentimentTopic() {
        return TopicBuilder.name("v2-sentiment")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic v2TrollTopic() {
        return TopicBuilder.name("v2-troll")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic v2ContextTopic() {
        return TopicBuilder.name("v2-context")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic v2AggregateTopic() {
        return TopicBuilder.name("v2-aggregate")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic v2AgentDlqTopic() {
        return TopicBuilder.name("v2-agent-dlq")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic v2BriefingDlqTopic() {
        return TopicBuilder.name("v2-briefing-dlq")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "gak-analyzer-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> batchKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setBatchListener(true);
        factory.setConcurrency(5);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> vodKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(5);
        return factory;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
