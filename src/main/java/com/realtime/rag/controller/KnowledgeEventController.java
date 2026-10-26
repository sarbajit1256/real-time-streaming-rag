/*package com.realtime.rag.controller;

import com.realtime.rag.event.KnowledgeEvent;
import com.realtime.rag.service.KnowledgeEventProducer;
import com.realtime.rag.service.PdfIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeEventController {
    private final KnowledgeEventProducer producer;
    private final PdfIngestionService pdfIngestionService;

    public KnowledgeEventController(KnowledgeEventProducer producer, PdfIngestionService pdfIngestionService) {
        this.producer = producer;
        this.pdfIngestionService = pdfIngestionService;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, String>> publishEvent(@Valid @RequestBody KnowledgeEvent event) {
        if (!"UPSERT".equalsIgnoreCase(event.operation()) && !"DELETE".equalsIgnoreCase(event.operation())) {
            return ResponseEntity.badRequest().body(Map.of("error", "operation must be UPSERT or DELETE"));
        }
        producer.publish(event);
        return ResponseEntity.accepted().body(Map.of("message", "Event published to Kafka", "eventId", event.eventId()));
    }

    @PostMapping(value = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadPdf(@RequestParam("file") MultipartFile file) throws IOException {
        String documentId = pdfIngestionService.upload(file);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "message", "PDF queued for ingestion", "documentId", documentId));
    }
}
*/



//Multiple Pdf Upload

package com.realtime.rag.controller;

import com.realtime.rag.event.KnowledgeEvent;
import com.realtime.rag.service.KnowledgeEventProducer;
import com.realtime.rag.service.KnowledgeIndexService;
import com.realtime.rag.service.PdfIngestionService;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeEventController {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeEventController.class);

    private final KnowledgeEventProducer producer;
    private final PdfIngestionService pdfIngestionService;
    private final KnowledgeIndexService knowledgeIndexService;

    public KnowledgeEventController(
            KnowledgeEventProducer producer,
            PdfIngestionService pdfIngestionService,
            KnowledgeIndexService knowledgeIndexService) {

        this.producer = producer;
        this.pdfIngestionService = pdfIngestionService;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    @GetMapping("/documents")
    public ResponseEntity<List<KnowledgeIndexService.DocumentSummary>> listDocuments() {
        return ResponseEntity.ok(knowledgeIndexService.listIndexedDocuments());
    }

    /**
     * Inspect PDF in memory: checks text hash and vocabulary overlap (Jaccard similarity).
     */
    @PostMapping(
            value = "/pdf/inspect",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> inspectPdf(
            @RequestParam("file") MultipartFile file) {

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "unnamed.pdf";
        }

        try {
            String extractedText = pdfIngestionService.extractText(file);
            String incomingHash = KnowledgeIndexService.computeSha256(extractedText);
            Set<String> incomingTokens = KnowledgeIndexService.tokenize(extractedText);

            Optional<KnowledgeIndexService.DocumentSummary> existingOpt =
                    knowledgeIndexService.findByTitle(filename);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("fileName", filename);
            response.put("contentHash", incomingHash);

            if (existingOpt.isEmpty()) {
                response.put("status", "NEW_DOCUMENT");
                response.put("message", "Document is new and ready for ingestion.");
                return ResponseEntity.ok(response);
            }

            KnowledgeIndexService.DocumentSummary existing = existingOpt.get();
            response.put("existingDocumentId", existing.documentId());
            response.put("existingVersion", existing.version());

            // 1. Exact Duplicate (Identical Text)
            if (incomingHash.equalsIgnoreCase(existing.contentHash())) {
                response.put("status", "EXACT_DUPLICATE");
                response.put("similarityPercentage", 100);
                response.put("message", "An identical copy of this document is already indexed.");
                return ResponseEntity.ok(response);
            }

            // 2. Compute token overlap (Jaccard similarity)
            Set<String> existingTokens = existing.distinctTokens();
            double similarity = 0.0;
            if (existingTokens != null && !existingTokens.isEmpty() && !incomingTokens.isEmpty()) {
                Set<String> intersection = new HashSet<>(incomingTokens);
                intersection.retainAll(existingTokens);

                Set<String> union = new HashSet<>(incomingTokens);
                union.addAll(existingTokens);

                similarity = (double) intersection.size() / union.size();
            }

            int similarityPercent = (int) Math.round(similarity * 100);
            response.put("similarityPercentage", similarityPercent);

            if (similarityPercent >= 25) {
                response.put("status", "REVISION_PAGE_CHANGES");
                response.put("message", "Document has modified pages or amendments (" + similarityPercent + "% content overlap).");
            } else {
                response.put("status", "CONFLICT_DIFFERENT_CONTENT");
                response.put("message", "A file with this name exists but the content is entirely different. Please rename the file.");
            }

            return ResponseEntity.ok(response);

        } catch (Exception ex) {
            log.error("Failed to inspect PDF: {}", filename, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of(
                            "status", "ERROR",
                            "message", "Could not inspect PDF: " + ex.getMessage()
                    )
            );
        }
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, String>> publishEvent(
            @Valid @RequestBody KnowledgeEvent event) {

        log.info("Received knowledge event: eventId={}, documentId={}, operation={}",
                event.eventId(), event.documentId(), event.operation());

        if (!"UPSERT".equalsIgnoreCase(event.operation())
                && !"DELETE".equalsIgnoreCase(event.operation())) {

            return ResponseEntity.badRequest().body(
                    Map.of("error", "operation must be UPSERT or DELETE")
            );
        }

        producer.publish(event);

        return ResponseEntity.accepted().body(
                Map.of(
                        "message", "Event published to Kafka",
                        "eventId", event.eventId()
                )
        );
    }

    @PostMapping(
            value = "/pdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Map<String, Object>> uploadPdfs(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) MultipartFile singleFile,
            @RequestParam(value = "replaceDocumentId", required = false) String replaceDocumentId,
            @RequestParam(value = "version", required = false) Integer explicitVersion) {

        List<MultipartFile> uploads = new ArrayList<>();
        if (files != null) uploads.addAll(files);
        if (singleFile != null && !singleFile.isEmpty()) uploads.add(singleFile);

        uploads.removeIf(file -> file == null || file.isEmpty());

        if (uploads.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of("message", "Please select at least one PDF file.")
            );
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successful = 0;
        int failed = 0;

        for (MultipartFile file : uploads) {
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                filename = "unnamed.pdf";
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", filename);

            try {
                String documentId;
                if (replaceDocumentId != null && !replaceDocumentId.isBlank()) {
                    int nextVersion = explicitVersion != null ? explicitVersion : 2;
                    documentId = pdfIngestionService.uploadReplacement(file, replaceDocumentId.trim(), nextVersion);
                    item.put("message", "PDF replacement queued for ingestion (v" + nextVersion + ")");
                } else {
                    documentId = pdfIngestionService.upload(file);
                    item.put("message", "PDF queued for ingestion");
                }

                item.put("success", true);
                item.put("documentId", documentId);
                successful++;
            } catch (IOException | IllegalArgumentException ex) {
                item.put("success", false);
                item.put("error", ex.getMessage() == null ? "Could not process this PDF." : ex.getMessage());
                failed++;
            } catch (Exception ex) {
                item.put("success", false);
                item.put("error", "Unexpected error while processing this PDF.");
                failed++;
            }

            results.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", successful + " PDF(s) queued, " + failed + " PDF(s) failed.");
        response.put("total", uploads.size());
        response.put("successful", successful);
        response.put("failed", failed);
        response.put("results", results);

        HttpStatus status = successful > 0 ? HttpStatus.ACCEPTED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }
}