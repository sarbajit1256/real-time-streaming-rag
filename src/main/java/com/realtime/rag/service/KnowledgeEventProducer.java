package com.realtime.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.rag.event.KnowledgeEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEventProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KnowledgeEventProducer(KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${app.kafka.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void publish(KnowledgeEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            // Keying by documentId preserves ordering for revisions of the same document.
            kafkaTemplate.send(topic, event.documentId(), payload).get();
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Could not serialize knowledge event", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not publish event to Kafka", ex);
        }
    }
}
