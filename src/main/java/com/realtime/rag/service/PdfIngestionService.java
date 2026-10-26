/*package com.realtime.rag.service;

import com.realtime.rag.event.KnowledgeEvent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Service
public class PdfIngestionService {
    private final KnowledgeEventProducer producer;
    public PdfIngestionService(KnowledgeEventProducer producer) { this.producer = producer; }

    public String upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose a PDF file.");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase().endsWith(".pdf"))
            throw new IllegalArgumentException("Only PDF files are supported.");
        String text;
        try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
            text = new PDFTextStripper().getText(pdf);
        }
        if (text == null || text.isBlank()) throw new IllegalArgumentException("No selectable text found. Scanned PDFs need OCR, which is not included in this MVP.");
        String id = UUID.randomUUID().toString();
        KnowledgeEvent event = new KnowledgeEvent(UUID.randomUUID().toString(), id,
                file.getOriginalFilename(), text, 1, Instant.now().toString(), null,
                "UPSERT", "pdf-upload");
        producer.publish(event);
        return id;
    }
}
*/



package com.realtime.rag.service;

import com.realtime.rag.event.KnowledgeEvent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class PdfIngestionService {

    private final KnowledgeEventProducer producer;

    public PdfIngestionService(KnowledgeEventProducer producer) {
        this.producer = producer;
    }

    /**
     * Extracts text from PDF bytes in memory, tagged with [[PAGE:n]] markers.
     */
    public String extractText(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a PDF file.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported.");
        }

        StringBuilder extractedText = new StringBuilder();

        try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
            if (pdf.isEncrypted()) {
                throw new IllegalArgumentException("Password-protected PDFs are not supported.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = pdf.getNumberOfPages();

            if (pageCount == 0) {
                throw new IllegalArgumentException("The uploaded PDF contains no pages.");
            }

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String pageText = stripper.getText(pdf);
                if (pageText != null && !pageText.isBlank()) {
                    extractedText
                            .append("\n\n[[PAGE:")
                            .append(page)
                            .append("]]\n")
                            .append(pageText.trim());
                }
            }
        } catch (IOException ex) {
            throw new IOException(
                    "Could not read the PDF. The file may be damaged or use an unsupported format.",
                    ex);
        }

        String text = extractedText.toString().trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    "No selectable text was found. Scanned PDFs require OCR, which is not yet implemented.");
        }

        return text;
    }

    /**
     * Standard upload: assigns a fresh document ID and sets version to 1.
     */
    public String upload(MultipartFile file) throws IOException {
        String documentId = UUID.randomUUID().toString();
        return ingestPdf(file, documentId, 1);
    }

    /**
     * Replacement upload: retains the existing document ID and sets version to 2 (or specified version).
     */
    public String uploadReplacement(MultipartFile file, String documentId) throws IOException {
        return uploadReplacement(file, documentId, 2);
    }

    /**
     * Replacement upload with explicit version assignment.
     */
    public String uploadReplacement(MultipartFile file, String documentId, int newVersion) throws IOException {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("Document ID is required for a replacement upload.");
        }
        return ingestPdf(file, documentId.trim(), Math.max(newVersion, 1));
    }

    /**
     * Internal ingestion pipeline that publishes the document knowledge event to Kafka.
     */
    private String ingestPdf(MultipartFile file, String documentId, int version) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "unnamed.pdf";
        }

        String text = extractText(file);

        KnowledgeEvent event = new KnowledgeEvent(
                UUID.randomUUID().toString(),
                documentId,
                filename,
                text,
                version,
                Instant.now().toString(),
                null,
                "UPSERT",
                "pdf-upload",
                "DOCUMENT",
                null,
                null,
                null
        );

        producer.publish(event);
        return documentId;
    }
}