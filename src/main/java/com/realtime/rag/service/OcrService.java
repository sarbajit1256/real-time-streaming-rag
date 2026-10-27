package com.realtime.rag.service;

import jakarta.annotation.PostConstruct;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.LoadLibs;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * High-performance Optical Character Recognition (OCR) service powered by Tesseract / Tess4J.
 * Extracts text from scanned document images and non-selectable PDF pages.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    @Value("${app.ocr.enabled:true}")
    private boolean enabled;

    @Value("${app.ocr.language:eng}")
    private String language;

    @Value("${app.ocr.dpi:200}")
    private int dpi;

    @Value("${app.ocr.tessdata-path:}")
    private String customTessdataPath;

    private volatile boolean available = false;
    private String resolvedDatapath;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("OCR is disabled by configuration (app.ocr.enabled=false).");
            available = false;
            return;
        }

        try {
            File datapathDir = null;

            if (customTessdataPath != null && !customTessdataPath.isBlank()) {
                File customDir = new File(customTessdataPath.trim());
                if (customDir.exists() && customDir.isDirectory()) {
                    datapathDir = customDir;
                    log.info("Using configured custom tessdata path: {}", datapathDir.getAbsolutePath());
                } else {
                    log.warn("Custom tessdata path '{}' not found. Falling back to bundled tessdata extraction.", customTessdataPath);
                }
            }

            if (datapathDir == null) {
                datapathDir = LoadLibs.extractTessResources("tessdata");
                log.info("Extracted bundled tessdata to: {}", datapathDir.getAbsolutePath());
            }

            this.resolvedDatapath = datapathDir.getAbsolutePath();

            // Verify that Tesseract instance can be initialized
            ITesseract probe = createTesseractInstance();
            probe.setPageSegMode(1); // Automatic page segmentation with OSD

            this.available = true;
            log.info("OCR Service initialized successfully. Language: '{}', Datapath: '{}', Default DPI: {}",
                    language, resolvedDatapath, dpi);

        } catch (Throwable t) {
            this.available = false;
            log.error("Failed to initialize OCR service. Scanned PDF OCR will be disabled. Reason: {}", t.getMessage(), t);
        }
    }

    /**
     * Checks if the OCR service is ready to process images.
     */
    public boolean isAvailable() {
        return enabled && available;
    }

    /**
     * Creates a configured ITesseract instance.
     */
    public ITesseract createTesseractInstance() {
        ITesseract tesseract = new Tesseract();
        if (resolvedDatapath != null) {
            tesseract.setDatapath(resolvedDatapath);
        }
        if (language != null && !language.isBlank()) {
            tesseract.setLanguage(language.trim());
        }
        return tesseract;
    }

    /**
     * Performs OCR on an in-memory BufferedImage.
     * Synchronized to guarantee thread-safe native execution across concurrent requests.
     */
    public synchronized String extractTextFromImage(BufferedImage image) {
        if (!isAvailable()) {
            throw new IllegalStateException("OCR service is not available. Please verify OCR configuration and tessdata.");
        }
        if (image == null) {
            return "";
        }

        long start = System.currentTimeMillis();
        try {
            ITesseract tesseract = createTesseractInstance();
            String rawText = tesseract.doOCR(image);
            long elapsed = System.currentTimeMillis() - start;

            String cleaned = rawText != null ? rawText.trim() : "";
            log.debug("OCR completed in {}ms. Image: {}x{} px. Extracted {} characters.",
                    elapsed, image.getWidth(), image.getHeight(), cleaned.length());

            return cleaned;
        } catch (TesseractException ex) {
            log.error("Tesseract OCR extraction failed: {}", ex.getMessage(), ex);
            throw new RuntimeException("OCR processing failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Convenience method to render and OCR a specific PDF page.
     *
     * @param document  Open PDDocument
     * @param pageIndex 0-based page index
     * @return Extracted OCR text or empty string
     */
    public String extractTextFromPdfPage(PDDocument document, int pageIndex) throws IOException {
        return extractTextFromPdfPage(document, pageIndex, this.dpi);
    }

    /**
     * Convenience method to render and OCR a specific PDF page with custom DPI.
     *
     * @param document  Open PDDocument
     * @param pageIndex 0-based page index
     * @param targetDpi Rendering DPI (typically 200-300)
     * @return Extracted OCR text or empty string
     */
    public String extractTextFromPdfPage(PDDocument document, int pageIndex, int targetDpi) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, targetDpi, ImageType.RGB);
        return extractTextFromImage(pageImage);
    }

    public int getDpi() {
        return dpi;
    }

    public String getLanguage() {
        return language;
    }
}
