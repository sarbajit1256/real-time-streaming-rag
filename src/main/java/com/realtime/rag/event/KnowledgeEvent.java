/*
package com.realtime.rag.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record KnowledgeEvent(

        @NotBlank
        String eventId,

        @NotBlank
        String documentId,

        @NotBlank
        String title,

        String content,

        @NotNull
        @Positive
        Integer version,

        @NotBlank
        String validFrom,

        String validTo,

        @NotBlank
        String operation,

        @NotBlank
        String source

) {
}*/



package com.realtime.rag.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Represents a versioned document change transported through Kafka.
 *
 * Supports:
 * - PDF document ingestion and updates
 * - Document deletion events
 * - Supplemental facts linked to an existing document
 * - Fact priority and historical validity
 *
 * Dates must be ISO-8601 instants.
 */
public record KnowledgeEvent(

        @NotBlank
        String eventId,

        @NotBlank
        String documentId,

        @NotBlank
        String title,

        String content,

        @NotNull
        @Positive
        Integer version,

        @NotBlank
        String validFrom,

        String validTo,

        @NotBlank
        String operation,

        @NotBlank
        String source,

        // DOCUMENT or SUPPLEMENTAL_FACT
        String eventType,

        // Stable identifier for a fact, e.g. nvidia_founder
        String factKey,

        // NORMAL or HIGH
        String priority,

        // Optional ID of the earlier fact this event supersedes
        String supersedesEventId

) {
}
