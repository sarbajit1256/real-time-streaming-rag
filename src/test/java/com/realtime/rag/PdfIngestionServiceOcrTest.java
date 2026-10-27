package com.realtime.rag;

import com.realtime.rag.service.KnowledgeEventProducer;
import com.realtime.rag.service.OcrService;
import com.realtime.rag.service.PdfIngestionService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PdfIngestionServiceOcrTest {

    private PdfIngestionService pdfIngestionService;
    private OcrService ocrService;
    private KnowledgeEventProducer producer;

    @BeforeEach
    void setUp() {
        ocrService = new OcrService();
        ReflectionTestUtils.setField(ocrService, "enabled", true);
        ReflectionTestUtils.setField(ocrService, "language", "eng");
        ReflectionTestUtils.setField(ocrService, "dpi", 200);
        ocrService.init();

        producer = Mockito.mock(KnowledgeEventProducer.class);
        pdfIngestionService = new PdfIngestionService(producer, ocrService);
        ReflectionTestUtils.setField(pdfIngestionService, "ocrEnabled", true);
        ReflectionTestUtils.setField(pdfIngestionService, "ocrDpi", 200);
        ReflectionTestUtils.setField(pdfIngestionService, "minTextLength", 20);
    }

    @Test
    void testExtractTextFromScannedPdfUsingOcr() throws IOException {
        // Create an image containing scanned text
        BufferedImage image = new BufferedImage(700, 250, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, 700, 250);
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("Arial", Font.BOLD, 26));
        g2d.drawString("ACCOUNT STATEMENT #44598", 40, 80);
        g2d.drawString("BALANCE DUE: $1,250.00", 40, 140);
        g2d.drawString("DUE DATE: 2026-11-15", 40, 200);
        g2d.dispose();

        // Put the image inside a PDF page with NO selectable digital text
        byte[] scannedPdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDImageXObject pdImage = JPEGFactory.createFromImage(doc, image);
            try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                contentStream.drawImage(pdImage, 50, 450, 500, 178);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            scannedPdfBytes = baos.toByteArray();
        }

        // Test extraction
        String extracted = pdfIngestionService.extractText(scannedPdfBytes, "scanned-statement.pdf");
        System.out.println("Extracted text from scanned PDF:\n" + extracted);

        assertTrue(extracted.contains("[[PAGE:1]]"), "Must preserve page markers");
        assertTrue(extracted.contains("ACCOUNT") || extracted.contains("STATEMENT"), "Should OCR document title");
        assertTrue(extracted.contains("44598") || extracted.contains("1,250") || extracted.contains("BALANCE"), "Should OCR financial details");
    }

    @Test
    void testExtractTextFromDigitalPdf() throws IOException {
        // Create a standard digital PDF with selectable text
        byte[] digitalPdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText("STANDARD DIGITAL CORPORATE WARRANTY POLICY 2026");
                contentStream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            digitalPdfBytes = baos.toByteArray();
        }

        String extracted = pdfIngestionService.extractText(digitalPdfBytes, "digital-policy.pdf");
        System.out.println("Extracted text from digital PDF:\n" + extracted);

        assertTrue(extracted.contains("[[PAGE:1]]"));
        assertTrue(extracted.contains("STANDARD DIGITAL CORPORATE WARRANTY POLICY"));
    }
}
