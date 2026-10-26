/*
package com.realtime.rag.service;

import com.realtime.rag.event.KnowledgeEvent;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeIndexService {

    private static final Pattern PAGE_MARKER =
            Pattern.compile("^\\s*\\[\\[PAGE:(\\d+)]]\\s*$");

    private final VectorStore vectorStore;
    private final TextChunkingService chunker;

    private final Set<String> processedEvents =
            ConcurrentHashMap.newKeySet();

    private final Map<String, Integer> latestVersionByDocument =
            new ConcurrentHashMap<>();

    
     // Runtime validity registry.
     
     // Each version has a start time and an optional end time.
     // Historical chunks remain in ChromaDB when a new version arrives.
     
     // NOTE: This registry is in memory. It must be made persistent
     // or rebuilt from a durable, ordered event history for production.
     
    private final Map<String, Map<Integer, VersionInterval>>
            versionHistory = new ConcurrentHashMap<>();

    
     //Tracks when a document was deleted.
     // A later UPSERT can make it available again.
     
    private final Map<String, Instant> deletedAtByDocument =
            new ConcurrentHashMap<>();

    public KnowledgeIndexService(
            VectorStore vectorStore,
            TextChunkingService chunker) {

        this.vectorStore = vectorStore;
        this.chunker = chunker;
    }

    
     // Applies an UPSERT or DELETE event.
     
    public synchronized String apply(KnowledgeEvent event) {

        validateEvent(event);
        validateDates(event);

        if (processedEvents.contains(event.eventId())) {
            return "duplicate-ignored";
        }

        String documentId = event.documentId().trim();

        String operation = event.operation()
                .trim()
                .toUpperCase(Locale.ROOT);

        int latestVersion =
                latestVersionByDocument.getOrDefault(documentId, 0);

        if (event.version() <= latestVersion) {
            processedEvents.add(event.eventId());
            return "stale-or-duplicate-version-ignored";
        }

        Instant eventTime = Instant.parse(event.validFrom());

        Map<Integer, VersionInterval> history =
                versionHistory.computeIfAbsent(
                        documentId,
                        ignored -> new ConcurrentHashMap<>());

        
         // Close the previous version's runtime validity interval.
         // Do not delete its chunks: historical queries still need them.
         
        if (latestVersion > 0) {
            VersionInterval previous = history.get(latestVersion);

            if (previous != null) {
                if (!eventTime.isAfter(previous.validFrom())) {
                    throw new IllegalArgumentException(
                            "A new version must start after the previous "
                                    + "version's validFrom.");
                }

                previous.setValidTo(eventTime);
            }
        }

        if ("DELETE".equals(operation)) {

            
             // Record the deletion time. Existing chunks are retained,
             // allowing queries dated before deletion to use historical
             // evidence.
             
            history.put(
                    event.version(),
                    new VersionInterval(eventTime, null, true));

            deletedAtByDocument.put(documentId, eventTime);

            latestVersionByDocument.put(
                    documentId, event.version());

            processedEvents.add(event.eventId());

            return "deleted-historically";
        }

        if (!"UPSERT".equals(operation)) {
            throw new IllegalArgumentException(
                    "operation must be UPSERT or DELETE.");
        }

        if (event.content() == null || event.content().isBlank()) {
            throw new IllegalArgumentException(
                    "content is required for UPSERT events.");
        }

        List<PageSection> pageSections =
                splitIntoPageSections(event.content());

        List<Document> documents = new ArrayList<>();

        int chunkIndex = 0;

        for (PageSection pageSection : pageSections) {

            List<String> chunks =
                    chunker.chunk(pageSection.text());

            for (String chunkText : chunks) {

                if (chunkText == null || chunkText.isBlank()) {
                    continue;
                }

                Map<String, Object> metadata =
                        metadata(event, chunkIndex);

                metadata.put(
                        "pageNumber",
                        pageSection.pageNumber());

                
                 //Use a version-specific ID so historical versions
                 //don't overwrite one another.
                 
                String chunkId = documentId
                        + "-v" + event.version()
                        + "-chunk-" + chunkIndex;

                documents.add(
                        new Document(
                                chunkId,
                                chunkText.trim(),
                                metadata));
                chunkIndex++;
            }
        }

        if (documents.isEmpty()) {
            throw new IllegalArgumentException(
                    "The document did not produce any non-empty chunks.");
        }

        
         //Insert the new version's chunks before committing runtime
         // version state. ChromaDB retains chunks from older versions.
         
        vectorStore.add(documents);

        history.put(
                event.version(),
                new VersionInterval(eventTime, null, false));

        deletedAtByDocument.remove(documentId);

        latestVersionByDocument.put(
                documentId, event.version());

        processedEvents.add(event.eventId());

        return "indexed-" + documents.size() + "-chunks";
    }

    
     // Search across all indexed documents.
     
    public List<Document> search(
            String question,
            Instant asOf,
            int topK) {

        validateSearchArguments(question, asOf, topK);

        int candidateCount = Math.max(topK * 10, 50);

        List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(candidateCount)
                        .build());

        return filterValidDocuments(
                candidates, asOf, topK, null);
    }

    
     // Search within one selected document.
     
     // A null or blank documentId searches across all documents.
     //
    public List<Document> search(
            String question,
            Instant asOf,
            int topK,
            String selectedDocumentId) {

        validateSearchArguments(question, asOf, topK);

        if (selectedDocumentId == null
                || selectedDocumentId.isBlank()) {
            return search(question, asOf, topK);
        }

        String documentId = selectedDocumentId.trim();

        
         // The document ID is generated by the application and should
         // be a UUID. Reject quote characters to avoid malformed filters.
         
        if (documentId.contains("'")) {
            throw new IllegalArgumentException(
                    "Invalid documentId.");
        }

        List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(Math.max(topK * 10, 50))
                        .filterExpression(
                                "documentId == '" + documentId + "'")
                        .build());

        return filterValidDocuments(
                candidates, asOf, topK, documentId);
    }

    
     // Applies temporal validity and document-scope checks.
     
    private List<Document> filterValidDocuments(
            List<Document> candidates,
            Instant asOf,
            int topK,
            String selectedDocumentId) {

        List<Document> valid = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (Document document : candidates) {

            Map<String, Object> metadata =
                    document.getMetadata();

            if (metadata == null) {
                continue;
            }

            String documentId =
                    String.valueOf(metadata.get("documentId"));

            if (selectedDocumentId != null
                    && !selectedDocumentId.equals(documentId)) {
                continue;
            }

            try {
                Object fromValue = metadata.get("validFrom");

                if (fromValue == null
                        || String.valueOf(fromValue).isBlank()) {
                    continue;
                }

                Instant storedValidFrom =
                        Instant.parse(String.valueOf(fromValue));

                if (asOf.isBefore(storedValidFrom)) {
                    continue;
                }

                int version = Integer.parseInt(
                        String.valueOf(metadata.get("version")));

                Map<Integer, VersionInterval> history =
                        versionHistory.get(documentId);

                
                 // When runtime history is available, use the closed
                 // interval rather than the original Chroma metadata.
                 
                VersionInterval interval = history == null
                        ? null
                        : history.get(version);

                Instant effectiveValidFrom = interval == null
                        ? storedValidFrom
                        : interval.validFrom();

                Instant effectiveValidTo = interval == null
                        ? parseOptionalInstant(metadata.get("validTo"))
                        : interval.validTo();

                if (asOf.isBefore(effectiveValidFrom)) {
                    continue;
                }

                if (effectiveValidTo != null
                        && !asOf.isBefore(effectiveValidTo)) {
                    continue;
                }

               
               //   A DELETE version is a control event, not evidence.
                 
                if (interval != null && interval.deleted()) {
                    continue;
                }

                boolean storedDeleted = Boolean.parseBoolean(
                        String.valueOf(
                                metadata.getOrDefault("deleted", false)));

                if (storedDeleted) {
                    continue;
                }

                String id = document.getId();

                if (id != null && !seenIds.add(id)) {
                    continue;
                }

                valid.add(document);

                if (valid.size() >= topK) {
                    break;
                }

            } catch (DateTimeParseException
                     | NumberFormatException
                     | NullPointerException ex) {
                // Invalid metadata cannot be trusted as evidence.
            }
        }

        return valid;
    }

    private Instant parseOptionalInstant(Object value) {

        if (value == null) {
            return null;
        }

        String text = String.valueOf(value);

        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }

        return Instant.parse(text);
    }

    private void validateSearchArguments(
            String question,
            Instant asOf,
            int topK) {

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException(
                    "Search question must not be empty.");
        }

        if (asOf == null) {
            throw new IllegalArgumentException(
                    "asOf must not be null.");
        }

        if (topK < 1) {
            throw new IllegalArgumentException(
                    "topK must be at least 1.");
        }
    }

    
     // Splits content using [[PAGE:n]] markers.
     // Text before the first marker defaults to page 1.
     
    private List<PageSection> splitIntoPageSections(String content) {

        List<PageSection> sections = new ArrayList<>();

        String normalized = content
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        StringBuilder currentText = new StringBuilder();
        int currentPage = 1;
        boolean foundMarker = false;

        for (String line : normalized.split("\n", -1)) {

            Matcher matcher = PAGE_MARKER.matcher(line);

            if (matcher.matches()) {

                if (currentText.length() > 0) {
                    addPageSection(
                            sections,
                            currentPage,
                            currentText.toString());
                    currentText.setLength(0);
                }

                currentPage = Integer.parseInt(matcher.group(1));
                foundMarker = true;

            } else {

                if (currentText.length() > 0) {
                    currentText.append('\n');
                }

                currentText.append(line);
            }
        }

        if (currentText.length() > 0) {
            addPageSection(
                    sections,
                    currentPage,
                    currentText.toString());
        }

        if (sections.isEmpty()
                && !foundMarker
                && !normalized.isBlank()) {
            sections.add(new PageSection(1, normalized.trim()));
        }

        return sections;
    }

    private void addPageSection(
            List<PageSection> sections,
            int pageNumber,
            String text) {

        if (text != null && !text.isBlank()) {
            sections.add(
                    new PageSection(pageNumber, text.trim()));
        }
    }

    private Map<String, Object> metadata(
            KnowledgeEvent event,
            int chunkIndex) {

        Map<String, Object> metadata = new HashMap<>();

        metadata.put("documentId", event.documentId());
        metadata.put("title", event.title());
        metadata.put("version", event.version());
        metadata.put("validFrom", event.validFrom());

        
         // The runtime history registry supplies the effective validTo
         // for prior versions. This metadata remains unchanged in Chroma.
         
        metadata.put(
                "validTo",
                event.validTo() == null ? "" : event.validTo());

        metadata.put(
                "source",
                event.source() == null ? "" : event.source());

        metadata.put("chunkIndex", chunkIndex);
        metadata.put("deleted", false);

        metadata.put(
                "operation",
                event.operation().trim().toUpperCase(Locale.ROOT));

        return metadata;
    }

    private void validateEvent(KnowledgeEvent event) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "Knowledge event must not be null.");
        }

        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException(
                    "eventId is required.");
        }

        if (event.documentId() == null
                || event.documentId().isBlank()) {
            throw new IllegalArgumentException(
                    "documentId is required.");
        }

        if (event.title() == null || event.title().isBlank()) {
            throw new IllegalArgumentException(
                    "title is required.");
        }

        if (event.version() == null || event.version() < 1) {
            throw new IllegalArgumentException(
                    "version must be a positive integer.");
        }

        if (event.operation() == null
                || event.operation().isBlank()) {
            throw new IllegalArgumentException(
                    "operation is required.");
        }
    }

    private void validateDates(KnowledgeEvent event) {

        try {
            if (event.validFrom() == null
                    || event.validFrom().isBlank()) {
                throw new IllegalArgumentException(
                        "validFrom is required.");
            }

            Instant validFrom =
                    Instant.parse(event.validFrom());

            if (event.validTo() != null
                    && !event.validTo().isBlank()) {

                Instant validTo =
                        Instant.parse(event.validTo());

                if (!validTo.isAfter(validFrom)) {
                    throw new IllegalArgumentException(
                            "validTo must be after validFrom.");
                }
            }

        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "validFrom and validTo must be ISO-8601 instants.",
                    ex);
        }
    }

    private record PageSection(
            int pageNumber,
            String text) {
    }

    private static final class VersionInterval {

        private final Instant validFrom;
        private volatile Instant validTo;
        private final boolean deleted;

        private VersionInterval(
                Instant validFrom,
                Instant validTo,
                boolean deleted) {
            this.validFrom = validFrom;
            this.validTo = validTo;
            this.deleted = deleted;
        }

        private Instant validFrom() {
            return validFrom;
        }

        private Instant validTo() {
            return validTo;
        }

        private boolean deleted() {
            return deleted;
        }

        private void setValidTo(Instant validTo) {
            this.validTo = validTo;
        }
    }
}*/

package com.realtime.rag.service;

import com.realtime.rag.event.KnowledgeEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class KnowledgeIndexService {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeIndexService.class);

    private static final Pattern PAGE_MARKER =
            Pattern.compile("^\\s*\\[\\[PAGE:(\\d+)]]\\s*$");

    private final VectorStore vectorStore;
    private final TextChunkingService chunker;

    private final Set<String> processedEvents =
            ConcurrentHashMap.newKeySet();

    private final Map<String, Integer> latestVersionByDocument =
            new ConcurrentHashMap<>();

    private final Map<String, Map<Integer, VersionInterval>>
            versionHistory = new ConcurrentHashMap<>();

    private final Map<String, FactRecord> factsByEventId =
            new ConcurrentHashMap<>();

    private final Map<String, Set<String>> factEventsByKey =
            new ConcurrentHashMap<>();

    // In-memory document registry with content hashing and token tracking
    private final Map<String, DocumentSummary> indexedDocuments =
            new ConcurrentHashMap<>();

    public record DocumentSummary(
            String documentId,
            String title,
            int version,
            int chunkCount,
            String validFrom,
            String contentHash,
            Set<String> distinctTokens
    ) {}

    public KnowledgeIndexService(
            VectorStore vectorStore,
            TextChunkingService chunker) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
    }

    /**
     * Restore document registry and content hashes from ChromaDB on startup.
     */
    @PostConstruct
    public void restoreDocumentsFromChroma() {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("the")
                            .topK(300)
                            .filterExpression("recordType == 'DOCUMENT'")
                            .build()
            );

            for (Document doc : docs) {
                Map<String, Object> meta = doc.getMetadata();
                if (meta == null) continue;

                String docId = value(meta, "documentId", "").trim();
                String title = value(meta, "title", "Uploaded PDF");
                int version = Integer.parseInt(value(meta, "version", "1"));
                String validFrom = value(meta, "validFrom", Instant.now().toString());
                String contentHash = value(meta, "contentHash", "");

                if (!docId.isBlank() && !indexedDocuments.containsKey(docId)) {
                    indexedDocuments.put(docId, new DocumentSummary(
                            docId,
                            title,
                            version,
                            1,
                            validFrom,
                            contentHash,
                            tokenize(doc.getText())
                    ));
                }
            }
            log.info("Restored {} document(s) into registry from ChromaDB.", indexedDocuments.size());
        } catch (Exception ex) {
            log.warn("Vector store query on startup skipped: {}", ex.getMessage());
        }
    }

    public List<DocumentSummary> listIndexedDocuments() {
        return indexedDocuments.values().stream()
                .sorted(Comparator.comparing(DocumentSummary::validFrom, Comparator.reverseOrder()))
                .toList();
    }

    public Optional<DocumentSummary> findByTitle(String title) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        return indexedDocuments.values().stream()
                .filter(doc -> doc.title() != null && doc.title().trim().equalsIgnoreCase(title.trim()))
                .findFirst();
    }

    public synchronized String apply(KnowledgeEvent event) {
        validateEvent(event);
        validateDates(event);

        if (processedEvents.contains(event.eventId())) {
            return "duplicate-ignored";
        }

        String eventType = normalize(
                event.eventType() == null
                        ? "DOCUMENT"
                        : event.eventType());

        return switch (eventType) {
            case "DOCUMENT" -> applyDocumentEvent(event);
            case "SUPPLEMENTAL_FACT" -> applySupplementalFact(event);
            default -> throw new IllegalArgumentException(
                    "eventType must be DOCUMENT or SUPPLEMENTAL_FACT.");
        };
    }

    private String applyDocumentEvent(KnowledgeEvent event) {
        String documentId = event.documentId().trim();
        String operation = normalize(event.operation());

        if (!operation.equals("UPSERT") && !operation.equals("DELETE")) {
            throw new IllegalArgumentException("operation must be UPSERT or DELETE.");
        }

        if (operation.equals("UPSERT") && (event.content() == null || event.content().isBlank())) {
            throw new IllegalArgumentException("content is required for UPSERT events.");
        }

        int eventVersion = (event.version() == null || event.version() < 1) ? 1 : event.version();
        int latestVersion = latestVersionByDocument.getOrDefault(documentId, 0);

        if (eventVersion <= latestVersion) {
            processedEvents.add(event.eventId());
            return "stale-or-duplicate-version-ignored";
        }

        Instant eventTime = Instant.parse(event.validFrom());

        Map<Integer, VersionInterval> history =
                versionHistory.computeIfAbsent(documentId, ignored -> new ConcurrentHashMap<>());

        VersionInterval previous = latestVersion == 0 ? null : history.get(latestVersion);

        if (previous != null && !eventTime.isAfter(previous.validFrom())) {
            throw new IllegalArgumentException(
                    "A new document version must start after previous version's validFrom.");
        }

        if (operation.equals("DELETE")) {
            if (previous != null) {
                previous.setValidTo(eventTime);
            }

            history.put(eventVersion, new VersionInterval(eventTime, null, true));
            latestVersionByDocument.put(documentId, eventVersion);
            indexedDocuments.remove(documentId);
            processedEvents.add(event.eventId());
            return "deleted-historically";
        }

        // Compute SHA-256 hash of extracted text
        String textHash = computeSha256(event.content());

        List<PageSection> sections = splitIntoPageSections(event.content());
        List<Document> documents = new ArrayList<>();
        int chunkIndex = 0;

        for (PageSection section : sections) {
            for (String chunk : chunker.chunk(section.text())) {
                if (chunk == null || chunk.isBlank()) {
                    continue;
                }

                Map<String, Object> metadata = new HashMap<>(documentMetadata(event));
                metadata.put("recordType", "DOCUMENT");
                metadata.put("version", eventVersion);
                metadata.put("pageNumber", section.pageNumber());
                metadata.put("chunkIndex", chunkIndex);
                metadata.put("deleted", false);
                metadata.put("contentHash", textHash);

                String chunkId = documentId + "-v" + eventVersion + "-chunk-" + chunkIndex;
                documents.add(new Document(chunkId, chunk.trim(), metadata));
                chunkIndex++;
            }
        }

        if (documents.isEmpty()) {
            throw new IllegalArgumentException("The document produced no non-empty chunks.");
        }

        vectorStore.add(documents);

        if (previous != null) {
            previous.setValidTo(eventTime);
        }

        history.put(eventVersion, new VersionInterval(eventTime, null, false));
        latestVersionByDocument.put(documentId, eventVersion);

        // Store into in-memory registry with token set for similarity comparison
        indexedDocuments.put(documentId, new DocumentSummary(
                documentId,
                event.title(),
                eventVersion,
                documents.size(),
                event.validFrom(),
                textHash,
                tokenize(event.content())
        ));

        processedEvents.add(event.eventId());
        return "indexed-" + documents.size() + "-chunks";
    }

    private String applySupplementalFact(KnowledgeEvent event) {
        String operation = normalize(event.operation());

        if (!operation.equals("UPSERT") && !operation.equals("DELETE")) {
            throw new IllegalArgumentException("Supplemental operation must be UPSERT or DELETE.");
        }

        if (event.factKey() == null || event.factKey().isBlank()) {
            throw new IllegalArgumentException("factKey is required for supplemental facts.");
        }

        if (event.content() == null || event.content().isBlank()) {
            throw new IllegalArgumentException("content is required for supplemental facts.");
        }

        String priority = event.priority() == null || event.priority().isBlank()
                ? "NORMAL"
                : normalize(event.priority());

        if (!priority.equals("NORMAL") && !priority.equals("HIGH")) {
            throw new IllegalArgumentException("priority must be NORMAL or HIGH.");
        }

        String factKey = event.factKey().trim();
        Instant validFrom = Instant.parse(event.validFrom());
        String key = factKeyFor(event.documentId().trim(), factKey);

        Set<String> existingIds = factEventsByKey.computeIfAbsent(
                key, ignored -> ConcurrentHashMap.newKeySet());

        String autoSupersededEventId = "";

        for (String priorEventId : existingIds) {
            FactRecord priorRecord = factsByEventId.get(priorEventId);
            if (priorRecord != null && priorRecord.validTo() == null && !priorRecord.deleted()) {
                if (!validFrom.isAfter(priorRecord.validFrom())) {
                    throw new IllegalArgumentException(
                            "A new fact update must start after active fact's validFrom ("
                                    + priorRecord.validFrom() + ").");
                }
                priorRecord.setValidTo(validFrom);
                autoSupersededEventId = priorEventId;
            }
        }

        int factVersion = (event.version() == null || event.version() < 1) ? 1 : event.version();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("recordType", "SUPPLEMENTAL_FACT");
        metadata.put("eventId", event.eventId());
        metadata.put("documentId", event.documentId().trim());
        metadata.put("title", event.title());
        metadata.put("factKey", factKey);
        metadata.put("priority", priority);
        metadata.put("version", factVersion);
        metadata.put("validFrom", event.validFrom());
        metadata.put("validTo", event.validTo() == null ? "" : event.validTo());
        metadata.put("source", event.source() == null ? "" : event.source());
        metadata.put("supersedesEventId", autoSupersededEventId);
        metadata.put("deleted", operation.equals("DELETE"));

        Document factDocument = new Document(
                "fact-" + event.eventId(),
                event.content().trim(),
                metadata);

        vectorStore.add(List.of(factDocument));

        FactRecord record = new FactRecord(
                event.eventId(),
                event.documentId().trim(),
                factKey,
                priority,
                validFrom,
                parseOptionalInstant(event.validTo()),
                operation.equals("DELETE"));

        factsByEventId.put(event.eventId(), record);
        existingIds.add(event.eventId());
        processedEvents.add(event.eventId());

        return operation.equals("DELETE")
                ? "supplemental-fact-deleted-historically"
                : "indexed-supplemental-fact";
    }

    public List<Document> search(String question, Instant asOf, int topK) {
        return search(question, asOf, topK, null);
    }

    public List<Document> search(String question, Instant asOf, int topK, String selectedDocumentId) {
        validateSearchArguments(question, asOf, topK);

        String filter = "recordType == 'DOCUMENT'";
        if (selectedDocumentId != null && !selectedDocumentId.isBlank()) {
            filter += " && documentId == '" + escapeFilterValue(selectedDocumentId.trim()) + "'";
        }

        List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(Math.max(topK * 10, 50))
                        .filterExpression(filter)
                        .build());

        return filterValidDocuments(candidates, asOf, topK, selectedDocumentId);
    }

    public List<Document> searchSupplementalFacts(String question, Instant asOf, int topK, String selectedDocumentId) {
        validateSearchArguments(question, asOf, topK);

        String filter = "recordType == 'SUPPLEMENTAL_FACT'";
        if (selectedDocumentId != null && !selectedDocumentId.isBlank()) {
            filter += " && documentId == '" + escapeFilterValue(selectedDocumentId.trim()) + "'";
        }

        List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(Math.max(topK * 10, 50))
                        .filterExpression(filter)
                        .build());

        List<Document> validFacts = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Document document : candidates) {
            Map<String, Object> metadata = document.getMetadata();
            if (metadata == null) continue;

            String eventId = value(metadata, "eventId", "");
            String documentId = value(metadata, "documentId", "");

            if (selectedDocumentId != null && !selectedDocumentId.isBlank()
                    && !selectedDocumentId.trim().equals(documentId)) {
                continue;
            }

            FactRecord record = factsByEventId.get(eventId);
            Instant validFrom;
            Instant validTo;
            boolean deleted;

            if (record != null) {
                validFrom = record.validFrom();
                validTo = record.validTo();
                deleted = record.deleted();
            } else {
                try {
                    validFrom = Instant.parse(value(metadata, "validFrom", ""));
                    validTo = parseOptionalInstant(metadata.get("validTo"));
                    deleted = Boolean.parseBoolean(value(metadata, "deleted", "false"));
                } catch (RuntimeException ex) {
                    continue;
                }
            }

            if (asOf.isBefore(validFrom)) continue;
            if (validTo != null && !asOf.isBefore(validTo)) continue;
            if (deleted) continue;
            if (eventId.isBlank() || !seen.add(eventId)) continue;

            validFacts.add(document);
        }

        validFacts.sort(
                Comparator.<Document, String>comparing(
                        doc -> value(doc.getMetadata(), "priority", "NORMAL"),
                        Comparator.reverseOrder()
                ).thenComparing(
                        doc -> {
                            String vf = value(doc.getMetadata(), "validFrom", "");
                            try {
                                return Instant.parse(vf);
                            } catch (Exception ex) {
                                return Instant.EPOCH;
                            }
                        },
                        Comparator.reverseOrder()
                )
        );

        return validFacts.stream().limit(topK).toList();
    }

    private List<Document> filterValidDocuments(
            List<Document> candidates,
            Instant asOf,
            int topK,
            String selectedDocumentId) {
        List<Document> valid = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (Document document : candidates) {
            Map<String, Object> metadata = document.getMetadata();
            if (metadata == null || !"DOCUMENT".equals(value(metadata, "recordType", "DOCUMENT"))) {
                continue;
            }

            String documentId = value(metadata, "documentId", "");
            if (selectedDocumentId != null && !selectedDocumentId.isBlank()
                    && !selectedDocumentId.trim().equals(documentId)) {
                continue;
            }

            try {
                Instant storedValidFrom = Instant.parse(value(metadata, "validFrom", ""));
                if (asOf.isBefore(storedValidFrom)) continue;

                int version = Integer.parseInt(value(metadata, "version", "1"));
                Map<Integer, VersionInterval> history = versionHistory.get(documentId);
                VersionInterval interval = history == null ? null : history.get(version);

                Instant effectiveFrom = interval == null ? storedValidFrom : interval.validFrom();
                Instant effectiveTo = interval == null ? parseOptionalInstant(metadata.get("validTo")) : interval.validTo();

                if (asOf.isBefore(effectiveFrom)) continue;
                if (effectiveTo != null && !asOf.isBefore(effectiveTo)) continue;
                if (interval != null && interval.deleted()) continue;
                if (Boolean.parseBoolean(value(metadata, "deleted", "false"))) continue;

                String id = document.getId();
                if (id != null && !seenIds.add(id)) continue;

                valid.add(document);
                if (valid.size() >= topK) break;

            } catch (RuntimeException ex) {
                // Ignore malformed temporal candidates
            }
        }
        return valid;
    }

    private Map<String, Object> documentMetadata(KnowledgeEvent event) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("recordType", "DOCUMENT");
        metadata.put("documentId", event.documentId().trim());
        metadata.put("title", event.title());
        metadata.put("version", (event.version() == null || event.version() < 1) ? 1 : event.version());
        metadata.put("validFrom", event.validFrom());
        metadata.put("validTo", event.validTo() == null ? "" : event.validTo());
        metadata.put("source", event.source() == null ? "" : event.source());
        metadata.put("operation", normalize(event.operation()));
        return metadata;
    }

    private List<PageSection> splitIntoPageSections(String content) {
        List<PageSection> sections = new ArrayList<>();
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');

        StringBuilder currentText = new StringBuilder();
        int currentPage = 1;
        boolean foundMarker = false;

        for (String line : normalized.split("\n", -1)) {
            Matcher matcher = PAGE_MARKER.matcher(line);
            if (matcher.matches()) {
                if (!currentText.isEmpty()) {
                    addPageSection(sections, currentPage, currentText.toString());
                    currentText.setLength(0);
                }
                currentPage = Integer.parseInt(matcher.group(1));
                foundMarker = true;
            } else {
                if (!currentText.isEmpty()) {
                    currentText.append('\n');
                }
                currentText.append(line);
            }
        }

        if (!currentText.isEmpty()) {
            addPageSection(sections, currentPage, currentText.toString());
        }

        if (sections.isEmpty() && !foundMarker && !normalized.isBlank()) {
            sections.add(new PageSection(1, normalized.trim()));
        }

        return sections;
    }

    private void addPageSection(List<PageSection> sections, int pageNumber, String text) {
        if (text != null && !text.isBlank()) {
            sections.add(new PageSection(pageNumber, text.trim()));
        }
    }

    public static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-zA-Z0-9]+"))
                .filter(w -> w.length() > 2)
                .collect(Collectors.toSet());
    }

    public static String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return String.valueOf(text.hashCode());
        }
    }

    private void validateSearchArguments(String question, Instant asOf, int topK) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Search question must not be empty.");
        }
        if (asOf == null) {
            throw new IllegalArgumentException("asOf must not be null.");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be at least 1.");
        }
    }

    private void validateEvent(KnowledgeEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Knowledge event must not be null.");
        }
        if (event.eventId() == null || event.eventId().isBlank()
                || event.documentId() == null || event.documentId().isBlank()
                || event.title() == null || event.title().isBlank()
                || event.operation() == null || event.operation().isBlank()
                || event.source() == null || event.source().isBlank()) {
            throw new IllegalArgumentException("eventId, documentId, title, operation, and source are required.");
        }
        if (event.version() != null && event.version() < 1) {
            throw new IllegalArgumentException("version must be a positive integer.");
        }
    }

    private void validateDates(KnowledgeEvent event) {
        try {
            if (event.validFrom() == null || event.validFrom().isBlank()) {
                throw new IllegalArgumentException("validFrom is required.");
            }
            Instant validFrom = Instant.parse(event.validFrom());
            if (event.validTo() != null && !event.validTo().isBlank()) {
                Instant validTo = Instant.parse(event.validTo());
                if (!validTo.isAfter(validFrom)) {
                    throw new IllegalArgumentException("validTo must be after validFrom.");
                }
            }
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("validFrom and validTo must be ISO-8601 instants.", ex);
        }
    }

    private Instant parseOptionalInstant(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) return null;
        return Instant.parse(text);
    }

    private String factKeyFor(String documentId, String factKey) {
        return documentId + "::" + factKey;
    }

    private String escapeFilterValue(String value) {
        if (!value.matches("[A-Za-z0-9_\\-\\.]+")) {
            throw new IllegalArgumentException("Invalid documentId: " + value);
        }
        return value;
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String value(Map<String, Object> metadata, String key, String defaultValue) {
        Object val = metadata.get(key);
        return val == null || String.valueOf(val).isBlank() ? defaultValue : String.valueOf(val);
    }

    private record PageSection(int pageNumber, String text) {}

    private static final class FactRecord {
        private final String eventId;
        private final String documentId;
        private final String factKey;
        private final String priority;
        private final Instant validFrom;
        private volatile Instant runtimeValidTo;
        private volatile boolean runtimeDeleted;

        private FactRecord(
                String eventId, String documentId, String factKey, String priority,
                Instant validFrom, Instant validTo, boolean deleted) {
            this.eventId = eventId;
            this.documentId = documentId;
            this.factKey = factKey;
            this.priority = priority;
            this.validFrom = validFrom;
            this.runtimeValidTo = validTo;
            this.runtimeDeleted = deleted;
        }

        private String eventId() { return eventId; }
        private String documentId() { return documentId; }
        private String factKey() { return factKey; }
        private String priority() { return priority; }
        private Instant validFrom() { return validFrom; }
        private Instant validTo() { return runtimeValidTo; }
        private boolean deleted() { return runtimeDeleted; }
        private void setValidTo(Instant value) { this.runtimeValidTo = value; }
        private void setDeleted(boolean value) { this.runtimeDeleted = value; }
    }

    private static final class VersionInterval {
        private final Instant validFrom;
        private volatile Instant validTo;
        private final boolean deleted;

        private VersionInterval(Instant validFrom, Instant validTo, boolean deleted) {
            this.validFrom = validFrom;
            this.validTo = validTo;
            this.deleted = deleted;
        }

        private Instant validFrom() { return validFrom; }
        private Instant validTo() { return validTo; }
        private boolean deleted() { return deleted; }
        private void setValidTo(Instant validTo) { this.validTo = validTo; }
    }
}