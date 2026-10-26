/*

package com.realtime.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagChatService {

    private static final Logger log =
            LoggerFactory.getLogger(RagChatService.class);

    private static final int MAX_ATTEMPTS = 3;

    private final KnowledgeIndexService indexService;
    private final ChatClient chatClient;

    public RagChatService(
            KnowledgeIndexService indexService,
            ChatClient.Builder builder) {

        this.indexService = indexService;
        this.chatClient = builder.build();
    }

    
      //Backward-compatible method.
      //Searches across all indexed documents.
     
    public ChatResult ask(String question, String asOfText) {
        return ask(question, asOfText, null);
    }

    
     // If documentId is provided, searches only that document.
     // If documentId is null or blank, searches all documents.
     
    public ChatResult ask(
            String question,
            String asOfText,
            String documentId) {

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException(
                    "Question must not be empty.");
        }

        String selectedDocumentId =
                documentId == null || documentId.isBlank()
                        ? null
                        : documentId.trim();

        Instant asOf;

        try {
            asOf = asOfText == null || asOfText.isBlank()
                    ? Instant.now()
                    : Instant.parse(asOfText.trim());

        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "asOf must be an ISO-8601 instant, "
                            + "e.g. 2026-10-22T00:00:00Z",
                    ex);
        }

        // Retrieve from one selected document or all documents.
        List<Document> sources = indexService.search(
                question,
                asOf,
                6,
                selectedDocumentId);

        String scopeDescription = selectedDocumentId == null
                ? "all indexed documents"
                : "document ID " + selectedDocumentId;

        if (sources.isEmpty()) {
            return new ChatResult(
                    "I couldn't find relevant information in "
                            + scopeDescription
                            + " for this question at "
                            + asOf
                            + ". Check whether the document has finished "
                            + "indexing, or try rephrasing your question.",
                    List.of(),
                    asOf.toString());
        }

        // Prepare source metadata for display in the UI.
        List<SourceInfo> infos = sources.stream()
                .map(document -> {
                    Map<String, Object> metadata =
                            document.getMetadata();

                    String text = document.getText() == null
                            ? ""
                            : document.getText();

                    return new SourceInfo(
                            metadataValue(
                                    metadata, "title", "Untitled"),
                            metadataValue(
                                    metadata, "documentId", "unknown"),
                            metadataValue(
                                    metadata, "version", "unknown"),
                            metadataValue(
                                    metadata, "validFrom", "unknown"),
                            metadataValue(
                                    metadata, "validTo", "open"),
                            metadataValue(
                                    metadata, "source", "unknown"),
                            metadataValue(
                                    metadata, "pageNumber", "unknown"),
                            text.length() > 260
                                    ? text.substring(0, 260) + "..."
                                    : text
                    );
                })
                .toList();

        // Include source identity and page information in the AI context.
        String context = sources.stream()
                .map(document -> {
                    Map<String, Object> metadata =
                            document.getMetadata();

                    return "Document title: "
                            + metadataValue(
                                    metadata, "title", "Untitled")
                            + "\nDocument ID: "
                            + metadataValue(
                                    metadata, "documentId", "unknown")
                            + "\nVersion: "
                            + metadataValue(
                                    metadata, "version", "unknown")
                            + "\nSource: "
                            + metadataValue(
                                    metadata, "source", "unknown")
                            + "\nPage: "
                            + metadataValue(
                                    metadata, "pageNumber", "unknown")
                            + "\nValid from: "
                            + metadataValue(
                                    metadata, "validFrom", "unknown")
                            + "\nValid until: "
                            + metadataValue(
                                    metadata, "validTo", "open")
                            + "\nPassage:\n"
                            + (document.getText() == null
                                    ? ""
                                    : document.getText());
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        try {
            String answer = generateAnswerWithRetry(
                    question,
                    context,
                    asOf,
                    selectedDocumentId);

            if (answer == null || answer.isBlank()) {
                answer = "The AI did not generate an answer. "
                        + "Please try rephrasing your question.";
            }

            return new ChatResult(
                    answer,
                    infos,
                    asOf.toString());

        } catch (RuntimeException ex) {
            log.error(
                    "Answer generation failed at {}",
                    asOf,
                    ex);

            return new ChatResult(
                    "The relevant documents were retrieved, but the AI "
                            + "could not generate an answer right now. "
                            + "This may be a temporary AI service issue. "
                            + "Please try again shortly.",
                    infos,
                    asOf.toString());
        }
    }

    private String generateAnswerWithRetry(
            String question,
            String context,
            Instant asOf,
            String documentId) {

        String scopeInstruction = documentId == null
                ? "The passages may come from multiple documents. "
                    + "Combine evidence from different documents when "
                    + "relevant. Identify the supporting document titles "
                    + "and page numbers."
                : "Answer using only passages from the selected document "
                    + "with ID " + documentId + ". Do not use passages "
                    + "from other documents.";

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return chatClient.prompt()
                        .system(
                                "You are a general-purpose PDF question-"
                                        + "answering assistant. "
                                        + "The documents may contain "
                                        + "educational, scientific, technical, "
                                        + "financial, legal, business, or "
                                        + "personal information. "
                                        + scopeInstruction
                                        + " Answer using only the supplied "
                                        + "passages. Do not invent facts or "
                                        + "introduce unsupported outside "
                                        + "knowledge. If the passages do not "
                                        + "contain enough evidence, clearly "
                                        + "explain what is missing. "
                                        + "For numerical questions, calculate "
                                        + "from the retrieved evidence when "
                                        + "possible and explain the basis. "
                                        + "When documents disagree, explain "
                                        + "the disagreement and identify the "
                                        + "documents involved. Cite document "
                                        + "titles and page numbers when "
                                        + "available. The requested knowledge "
                                        + "time is " + asOf
                                        + ". Respect validity dates when "
                                        + "available."
                        )
                        .user(
                                "Question:\n"
                                        + question
                                        + "\n\nRetrieved document passages:\n"
                                        + context
                        )
                        .call()
                        .content();

            } catch (RuntimeException ex) {

                if (!isTemporaryOverload(ex)
                        || attempt == MAX_ATTEMPTS) {
                    throw ex;
                }

                long delaySeconds = 2L * attempt;

                log.warn(
                        "Gemini temporary overload. Attempt {}/{}. "
                                + "Retrying in {} seconds.",
                        attempt,
                        MAX_ATTEMPTS,
                        delaySeconds);

                try {
                    Thread.sleep(delaySeconds * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();

                    throw new IllegalStateException(
                            "Interrupted while waiting to retry Gemini.",
                            interrupted);
                }
            }
        }

        throw new IllegalStateException(
                "Gemini answer generation failed after retries.");
    }

    private boolean isTemporaryOverload(Throwable error) {
        Throwable current = error;

        while (current != null) {
            String message = current.getMessage();

            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);

                if (lower.contains("503")
                        && (lower.contains("high demand")
                        || lower.contains("overload")
                        || lower.contains("temporarily unavailable"))) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    private String metadataValue(
            Map<String, Object> metadata,
            String key,
            String defaultValue) {

        if (metadata == null) {
            return defaultValue;
        }

        Object value = metadata.get(key);

        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }

        return String.valueOf(value);
    }

    public record ChatResult(
            String answer,
            List<SourceInfo> sources,
            String asOf) {
    }

    public record SourceInfo(
            String title,
            String documentId,
            String version,
            String validFrom,
            String validTo,
            String source,
            String pageNumber,
            String excerpt) {
    }
}
*/








package com.realtime.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagChatService {

    private static final Logger log =
            LoggerFactory.getLogger(RagChatService.class);

    private static final int MAX_ATTEMPTS = 3;

    private static final int PDF_TOP_K = 6;
    private static final int FACT_TOP_K = 30;

    private static final String DOCUMENT_TYPE = "DOCUMENT";
    private static final String SUPPLEMENTAL_FACT_TYPE = "SUPPLEMENTAL_FACT";

    private final KnowledgeIndexService indexService;
    private final ChatClient chatClient;

    public RagChatService(
            KnowledgeIndexService indexService,
            ChatClient.Builder builder) {

        this.indexService = indexService;
        this.chatClient = builder.build();
    }

    public ChatResult ask(String question, String asOfText) {
        return ask(question, asOfText, null);
    }

    public ChatResult ask(
            String question,
            String asOfText,
            String documentId) {

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException(
                    "Question must not be empty.");
        }

        String selectedDocumentId =
                documentId == null || documentId.isBlank()
                        ? null
                        : documentId.trim();

        Instant asOf;

        try {
            asOf = asOfText == null || asOfText.isBlank()
                    ? Instant.now()
                    : Instant.parse(asOfText.trim());

        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "asOf must be an ISO-8601 instant, e.g. 2026-10-22T00:00:00Z",
                    ex);
        }

        String scopeDescription = selectedDocumentId == null
                ? "all indexed documents"
                : "document ID " + selectedDocumentId;

        // 1. Retrieve ordinary PDF passages
        List<Document> pdfSources = indexService.search(
                question,
                asOf,
                PDF_TOP_K,
                selectedDocumentId);

        // 2. Retrieve supplemental facts
        List<Document> retrievedFacts =
                indexService.searchSupplementalFacts(
                        question,
                        asOf,
                        FACT_TOP_K,
                        selectedDocumentId);

        if (retrievedFacts == null) {
            retrievedFacts = List.of();
        }

        // 3. Resolve conflicting versions: newest validFrom wins
        List<Document> supplementalFacts =
                resolveSupplementalFactConflicts(
                        retrievedFacts,
                        asOf);

        // 4. Combined source list: supplemental facts listed first for priority
        List<Document> allSources = new ArrayList<>();
        allSources.addAll(supplementalFacts);
        allSources.addAll(pdfSources);

        if (allSources.isEmpty()) {
            return new ChatResult(
                    "I couldn't find relevant information in "
                            + scopeDescription
                            + " for this question at "
                            + asOf
                            + ". Check whether indexing has finished, "
                            + "or try rephrasing your question.",
                    List.of(),
                    asOf.toString());
        }

        // 5. Prepare source metadata for the UI (Needed to populate + Patch Fact button)
        List<SourceInfo> infos = allSources.stream()
                .map(this::toSourceInfo)
                .toList();

        // 6. Build structured prompt context ensuring supplemental facts are positioned at the very top
        StringBuilder contextBuilder = new StringBuilder();

        if (!supplementalFacts.isEmpty()) {
            contextBuilder.append("=== LATEST SUPPLEMENTAL FACTS & OVERRIDES (HIGHEST PRIORITY) ===\n\n");
            for (Document fact : supplementalFacts) {
                contextBuilder.append(buildContextEntry(fact, asOf))
                              .append("\n\n--------------------------------\n\n");
            }
        }

        if (!pdfSources.isEmpty()) {
            contextBuilder.append("=== ORIGINAL DOCUMENT PASSAGES ===\n\n");
            for (Document doc : pdfSources) {
                contextBuilder.append(buildContextEntry(doc, asOf))
                              .append("\n\n--------------------------------\n\n");
            }
        }

        String context = contextBuilder.toString().trim();

        try {
            String answer = generateAnswerWithRetry(
                    question,
                    context,
                    asOf,
                    selectedDocumentId);

            if (answer == null || answer.isBlank()) {
                answer = "The AI did not generate an answer. "
                        + "Please try rephrasing your question.";
            }

            return new ChatResult(
                    answer,
                    infos,
                    asOf.toString());

        } catch (RuntimeException ex) {
            log.error(
                    "Answer generation failed at {}",
                    asOf,
                    ex);

            return new ChatResult(
                    "The relevant information was retrieved, but the AI "
                            + "could not generate an answer right now. "
                            + "This may be a temporary AI service issue. "
                            + "Please try again shortly.",
                    infos,
                    asOf.toString());
        }
    }

    private List<Document> resolveSupplementalFactConflicts(
            List<Document> facts,
            Instant asOf) {

        if (facts == null || facts.isEmpty()) {
            return List.of();
        }

        Map<String, List<Document>> factsByKey =
                facts.stream()
                        .filter(document -> isApplicableAt(document, asOf))
                        .collect(Collectors.groupingBy(
                                this::factGroupKey,
                                LinkedHashMap::new,
                                Collectors.toList()));

        List<Document> resolved = new ArrayList<>();

        for (List<Document> versions : factsByKey.values()) {
            versions.sort(
                    Comparator
                            .comparing(
                                    this::validFromInstant,
                                    Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparingInt(this::priorityRank)
            );

            Document selected = versions.get(versions.size() - 1);
            resolved.add(selected);
        }

        resolved.sort(
                Comparator
                        .comparing(
                                this::validFromInstant,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(document ->
                                metadataValue(document.getMetadata(), "priority", "NORMAL"),
                                Comparator.reverseOrder())
        );

        return resolved;
    }

    private String factGroupKey(Document document) {
        Map<String, Object> metadata = document.getMetadata();

        String documentId = metadataValue(
                metadata, "documentId", "unknown");

        String factKey = metadataValue(
                metadata, "factKey", "");

        if (factKey.isBlank()) {
            factKey = metadataValue(
                    metadata,
                    "eventId",
                    document.getId() == null
                            ? String.valueOf(System.identityHashCode(document))
                            : document.getId());
        }

        return documentId + "::" + factKey;
    }

    private Instant validFromInstant(Document document) {
        String value = metadataValue(
                document.getMetadata(),
                "validFrom",
                "");

        if (value.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            log.warn("Invalid supplemental-fact validFrom: {}", value);
            return null;
        }
    }

    private int priorityRank(Document document) {
        String priority = metadataValue(
                document.getMetadata(),
                "priority",
                "NORMAL");

        return "HIGH".equalsIgnoreCase(priority) ? 1 : 0;
    }

    private boolean isApplicableAt(
            Document document,
            Instant asOf) {

        Map<String, Object> metadata = document.getMetadata();

        String validFromText = metadataValue(
                metadata, "validFrom", "");

        if (!validFromText.isBlank()) {
            try {
                Instant validFrom = Instant.parse(validFromText);
                if (validFrom.isAfter(asOf)) {
                    return false;
                }
            } catch (DateTimeParseException ex) {
                log.warn("Could not parse validFrom '{}'", validFromText);
            }
        }

        String validToText = metadataValue(
                metadata, "validTo", "");

        if (!validToText.isBlank()
                && !"open".equalsIgnoreCase(validToText)
                && !"null".equalsIgnoreCase(validToText)) {

            try {
                Instant validTo = Instant.parse(validToText);
                if (!asOf.isBefore(validTo)) {
                    return false;
                }
            } catch (DateTimeParseException ex) {
                log.warn("Could not parse validTo '{}'", validToText);
            }
        }

        return true;
    }

    private SourceInfo toSourceInfo(Document document) {
        Map<String, Object> metadata = document.getMetadata();

        String recordType = metadataValue(
                metadata, "recordType", DOCUMENT_TYPE);

        String text = document.getText() == null
                ? ""
                : document.getText();

        String excerpt = text.length() > 260
                ? text.substring(0, 260) + "..."
                : text;

        String pageNumber = metadataValue(
                metadata, "pageNumber", "unknown");

        if (SUPPLEMENTAL_FACT_TYPE.equalsIgnoreCase(recordType)) {
            pageNumber = "Supplemental fact";
        }

        return new SourceInfo(
                metadataValue(metadata, "title", "Untitled"),
                metadataValue(metadata, "documentId", "unknown"),
                metadataValue(metadata, "version", "unknown"),
                metadataValue(metadata, "validFrom", "unknown"),
                metadataValue(metadata, "validTo", "open"),
                metadataValue(metadata, "source", "unknown"),
                pageNumber,
                excerpt
        );
    }

    private String buildContextEntry(
            Document document,
            Instant asOf) {

        Map<String, Object> metadata = document.getMetadata();

        String recordType = metadataValue(
                metadata, "recordType", DOCUMENT_TYPE);

        String text = document.getText() == null
                ? ""
                : document.getText();

        if (SUPPLEMENTAL_FACT_TYPE.equalsIgnoreCase(recordType)) {
            return "[SUPPLEMENTAL FACT (CURRENT OVERRIDE)]"
                    + "\nDocument title: "
                    + metadataValue(metadata, "title", "Untitled")
                    + "\nDocument ID: "
                    + metadataValue(metadata, "documentId", "unknown")
                    + "\nFact key: "
                    + metadataValue(metadata, "factKey", "unknown")
                    + "\nPriority: "
                    + metadataValue(metadata, "priority", "NORMAL")
                    + "\nVersion: "
                    + metadataValue(metadata, "version", "unknown")
                    + "\nValid from: "
                    + metadataValue(metadata, "validFrom", "unknown")
                    + "\nValid until: "
                    + metadataValue(metadata, "validTo", "open")
                    + "\nFact content:\n"
                    + text;
        }

        return "[DOCUMENT PASSAGE]"
                + "\nDocument title: "
                + metadataValue(metadata, "title", "Untitled")
                + "\nDocument ID: "
                + metadataValue(metadata, "documentId", "unknown")
                + "\nVersion: "
                + metadataValue(metadata, "version", "unknown")
                + "\nPage: "
                + metadataValue(metadata, "pageNumber", "unknown")
                + "\nValid from: "
                + metadataValue(metadata, "validFrom", "unknown")
                + "\nValid until: "
                + metadataValue(metadata, "validTo", "open")
                + "\nPassage:\n"
                + text;
    }

    private String generateAnswerWithRetry(
            String question,
            String context,
            Instant asOf,
            String documentId) {

        String scopeInstruction = documentId == null
                ? "Combine evidence from available documents when appropriate."
                : "Use evidence only from the selected document ID: " + documentId + ".";

        String systemInstruction =
                "You are an accurate, direct question-answering assistant specializing in real-time verified knowledge retrieval. "
                        + scopeInstruction
                        + "\n\nCRITICAL CONSTRAINTS & FORMATTING RULES:"
                        + "\n1. Provide ONLY the direct, synthesized final answer to the user's question."
                        + "\n2. ALWAYS apply the latest Supplemental Fact overrides as the current truth if available."
                        + "\n3. DO NOT output citation lines, file references, or metadata (e.g., do NOT write 'Source:', 'Sources:', '(RAG_Text_Only_Notes.pdf, page 2)', etc.)."
                        + "\n4. DO NOT add sections like 'Supporting details from earlier passages', 'Historical context', or raw chunk quotes."
                        + "\n5. Answer using ONLY the provided verified context. Do not invent facts."
                        + "\n6. Format your answer cleanly using standard Markdown formatting (bold, bullet points) where appropriate.";

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return chatClient.prompt()
                        .system(systemInstruction)
                        .user(
                                "Question:\n"
                                        + question
                                        + "\n\nKnowledge context:\n"
                                        + context
                        )
                        .call()
                        .content();

            } catch (RuntimeException ex) {

                if (!isTemporaryOverload(ex) || attempt == MAX_ATTEMPTS) {
                    throw ex;
                }

                long delaySeconds = 2L * attempt;

                log.warn(
                        "LLM temporary overload. Attempt {}/{}. Retrying in {} seconds.",
                        attempt,
                        MAX_ATTEMPTS,
                        delaySeconds);

                try {
                    Thread.sleep(delaySeconds * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting to retry LLM call.",
                            interrupted);
                }
            }
        }

        throw new IllegalStateException("LLM answer generation failed after retries.");
    }

    private boolean isTemporaryOverload(Throwable error) {
        Throwable current = error;

        while (current != null) {
            String message = current.getMessage();

            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);

                if (lower.contains("503")
                        && (lower.contains("high demand")
                        || lower.contains("overload")
                        || lower.contains("temporarily unavailable"))) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    private String metadataValue(
            Map<String, Object> metadata,
            String key,
            String defaultValue) {

        if (metadata == null) {
            return defaultValue;
        }

        Object value = metadata.get(key);

        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }

        return String.valueOf(value);
    }

    public record ChatResult(
            String answer,
            List<SourceInfo> sources,
            String asOf) {
    }

    public record SourceInfo(
            String title,
            String documentId,
            String version,
            String validFrom,
            String validTo,
            String source,
            String pageNumber,
            String excerpt) {
    }
}