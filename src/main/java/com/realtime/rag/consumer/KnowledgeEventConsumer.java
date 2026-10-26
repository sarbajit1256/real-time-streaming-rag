/*




package com.realtime.rag.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.rag.event.KnowledgeEvent;
import com.realtime.rag.service.KnowledgeIndexService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeEventConsumer.class);

    private final ObjectMapper mapper;
    private final KnowledgeIndexService indexService;

    public KnowledgeEventConsumer(
            ObjectMapper mapper,
            KnowledgeIndexService indexService) {

        this.mapper = mapper;
        this.indexService = indexService;
    }

    @KafkaListener(topics = "${app.kafka.topic}")
    public void consume(String payload) throws Exception {

        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException(
                    "Received an empty knowledge event from Kafka.");
        }

        KnowledgeEvent event;

        try {
            event = mapper.readValue(payload, KnowledgeEvent.class);
        } catch (Exception ex) {
            log.error(
                    "Failed to deserialize knowledge event. Payload length={}",
                    payload.length(),
                    ex);

            // Propagate the failure so Kafka's configured error handling
            // can handle the failed record.
            throw ex;
        }

        try {
            String result = indexService.apply(event);

            log.info(
                    "Processed eventId={} documentId={} title={} "
                    + "version={} source={} result={}",
                    event.eventId(),
                    event.documentId(),
                    event.title(),
                    event.version(),
                    event.source(),
                    result);

        } catch (Exception ex) {

            log.error(
                    "Failed to process eventId={} documentId={} title={}",
                    event.eventId(),
                    event.documentId(),
                    event.title(),
                    ex);

            throw ex;
        }
    }
}
*/




package com.realtime.rag.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.rag.event.KnowledgeEvent;
import com.realtime.rag.service.KnowledgeIndexService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeEventConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeEventConsumer.class);

    private final ObjectMapper mapper;
    private final KnowledgeIndexService indexService;

    public KnowledgeEventConsumer(
            ObjectMapper mapper,
            KnowledgeIndexService indexService) {

        this.mapper = mapper;
        this.indexService = indexService;
    }

    @KafkaListener(topics = "${app.kafka.topic}")
    public void consume(String payload) throws Exception {

        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException(
                    "Received an empty knowledge event from Kafka.");
        }

        KnowledgeEvent event;

        try {
            event = mapper.readValue(payload, KnowledgeEvent.class);
        } catch (Exception ex) {
            log.error(
                    "Failed to deserialize knowledge event. Payload length={}",
                    payload.length(),
                    ex);
            throw ex;
        }

        String eventType =
                event.eventType() == null || event.eventType().isBlank()
                        ? "DOCUMENT"
                        : event.eventType().trim().toUpperCase();

        try {
            log.info(
                    "Received knowledge event: eventId={}, type={}, " +
                    "documentId={}, factKey={}, priority={}, version={}",
                    event.eventId(),
                    eventType,
                    event.documentId(),
                    event.factKey(),
                    event.priority(),
                    event.version());

            String result = indexService.apply(event);

            log.info(
                    "Processed knowledge event: eventId={}, type={}, " +
                    "documentId={}, title={}, version={}, source={}, result={}",
                    event.eventId(),
                    eventType,
                    event.documentId(),
                    event.title(),
                    event.version(),
                    event.source(),
                    result);

        } catch (Exception ex) {
            log.error(
                    "Failed to process knowledge event: eventId={}, " +
                    "type={}, documentId={}, factKey={}",
                    event.eventId(),
                    eventType,
                    event.documentId(),
                    event.factKey(),
                    ex);

            throw ex;
        }
    }
}
