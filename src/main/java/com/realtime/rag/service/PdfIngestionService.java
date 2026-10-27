package com.realtime.rag.service;

import com.realtime.rag.event.KnowledgeEvent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class PdfIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PdfIngestionService.class);

    private final KnowledgeEventProducer producer;
    private final OcrService ocrService;

    @Value("${app.ocr.enabled:true}")
    private boolean ocrEnabled;

    @Value("${app.ocr.dpi:200}")
    private int ocrDpi;

    @Value("${app.ocr.min-text-length:20}")
    private int minTextLength;

    public PdfIngestionService(KnowledgeEventProducer producer, OcrService ocrService) {
        this.producer = producer;
        this.ocrService = ocrService;
    }

    /**
     * Extracts text from PDF bytes in memory, tagged with [[PAGE:n]] markers.
     * Uses hybrid extraction: native digital text extraction with automatic OCR fallback for scanned pages.
     */
    public String extractText(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a PDF file.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported.");
        }

        return extractText(file.getBytes(), filename);
    }

    /**
     * Extracts text from raw PDF bytes in memory, tagged with [[PAGE:n]] markers.
     * Seamlessly handles both standard selectable PDFs and scanned image-only PDFs using OCR.
     */
    public String extractText(byte[] pdfBytes, String filename) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF content is empty.");
        }

        String safeFilename = (filename == null || filename.isBlank()) ? "unnamed.pdf" : filename;
        StringBuilder extractedText = new StringBuilder();

        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            if (pdf.isEncrypted()) {
                throw new IllegalArgumentException("Password-protected PDFs are not supported.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = pdf.getNumberOfPages();

            if (pageCount == 0) {
                throw new IllegalArgumentException("The uploaded PDF contains no pages.");
            }

            PDFRenderer renderer = null;
            int digitalPagesCount = 0;
            int ocrPagesCount = 0;

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String pageText = stripper.getText(pdf);
                int alphaNumCount = countAlphaNumeric(pageText);

                if (alphaNumCount >= minTextLength) {
                    // Page has substantial selectable digital text
                    extractedText
                            .append("\n\n[[PAGE:")
                            .append(page)
                            .append("]]\n")
                            .append(pageText.trim());
                    digitalPagesCount++;
                } else if (ocrEnabled && ocrService.isAvailable()) {
                    // Page appears to be scanned or contains image-only content
                    log.info("Page {}/{} in '{}' has insufficient selectable text ({} chars). Running OCR at {} DPI...",
                            page, pageCount, safeFilename, alphaNumCount, ocrDpi);
                    try {
                        if (renderer == null) {
                            renderer = new PDFRenderer(pdf);
                        }
                        BufferedImage pageImage = renderer.renderImageWithDPI(page - 1, ocrDpi, ImageType.RGB);
                        String ocrText = ocrService.extractTextFromImage(pageImage);

                        if (ocrText != null && !ocrText.isBlank()) {
                            extractedText
                                    .append("\n\n[[PAGE:")
                                    .append(page)
                                    .append("]]\n")
                                    .append(ocrText.trim());
                            ocrPagesCount++;
                            log.info("OCR successfully extracted {} chars from page {}/{} of '{}'",
                                    ocrText.trim().length(), page, pageCount, safeFilename);
                        } else if (pageText != null && !pageText.isBlank()) {
                            // Retain minor digital text if OCR did not yield additional content
                            extractedText
                                    .append("\n\n[[PAGE:")
                                    .append(page)
                                    .append("]]\n")
                                    .append(pageText.trim());
                        }
                    } catch (Exception ex) {
                        log.warn("OCR failed on page {} of '{}': {}", page, safeFilename, ex.getMessage());
                        if (pageText != null && !pageText.isBlank()) {
                            extractedText
                                    .append("\n\n[[PAGE:")
                                    .append(page)
                                    .append("]]\n")
                                    .append(pageText.trim());
                        }
                    }
                } else {
                    // OCR is not available or disabled; retain whatever native text exists
                    if (pageText != null && !pageText.isBlank()) {
                        extractedText
                                .append("\n\n[[PAGE:")
                                .append(page)
                                .append("]]\n")
                                .append(pageText.trim());
                    }
                }
            }

            log.info("PDF extraction finished for '{}': totalPages={}, digitalPages={}, ocrPages={}, totalCharacters={}",
                    safeFilename, pageCount, digitalPagesCount, ocrPagesCount, extractedText.length());

        } catch (IOException ex) {
            throw new IOException(
                    "Could not read the PDF. The file may be damaged or use an unsupported format.",
                    ex);
        }

        String text = extractedText.toString().trim();
        if (text.isBlank()) {
            if (ocrEnabled && ocrService.isAvailable()) {
                throw new IllegalArgumentException(
                        "No text could be extracted from '" + safeFilename + "' using either standard parser or OCR. The document may be blank or contains illegible scans.");
            } else {
                throw new IllegalArgumentException(
                        "No selectable text was found in '" + safeFilename + "'. Scanned PDFs require OCR, which is currently unavailable.");
            }
        }

        return text;
    }

    private static int countAlphaNumeric(String str) {
        if (str == null) return 0;
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (Character.isLetterOrDigit(str.charAt(i))) {
                count++;
            }
        }
        return count;
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