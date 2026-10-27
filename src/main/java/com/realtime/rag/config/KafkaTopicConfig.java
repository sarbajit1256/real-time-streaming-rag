package com.realtime.rag.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "app.kafka.auto-create-topic", havingValue = "true", matchIfMissing = true)
public class KafkaTopicConfig {

    @Value("${app.kafka.topic-partitions:3}")
    private int partitions;

    @Value("${app.kafka.topic-replicas:1}")
    private int replicas;

    @Bean
    public NewTopic knowledgeEventsTopic(@Value("${app.kafka.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }
}
