package com.realtime.rag;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.util.LoadLibs;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class OcrSmokeTest {

    @Test
    void testTesseractOcr() throws Exception {
        File tessDataFolder = LoadLibs.extractTessResources("tessdata");
        System.out.println("Extracted tessdata to: " + tessDataFolder.getAbsolutePath());

        ITesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessDataFolder.getAbsolutePath());
        tesseract.setLanguage("eng");

        // Create an image with text "INVOICE #98234 TOTAL $500"
        BufferedImage image = new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, 400, 100);
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("Arial", Font.BOLD, 24));
        g2d.drawString("INVOICE #98234 TOTAL $500", 20, 50);
        g2d.dispose();

        String result = tesseract.doOCR(image);
        System.out.println("OCR Result: " + result);

        assertTrue(result.contains("INVOICE") || result.contains("98234"), "OCR should recognize text");
    }

    @Test
    void testPdfBoxRenderWithOcr() throws Exception {
        // Create a PDF with an image containing scanned text
        File tessDataFolder = LoadLibs.extractTessResources("tessdata");
        ITesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessDataFolder.getAbsolutePath());
        tesseract.setLanguage("eng");

        // Generate a 1-page PDF containing a drawn image (simulating a scanned receipt)
        byte[] pdfBytes;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.LETTER);
            doc.addPage(page);

            BufferedImage image = new BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, 600, 200);
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("Arial", Font.BOLD, 28));
            g2d.drawString("SCANNED POLICY DOCUMENT #789", 30, 80);
            g2d.drawString("COVERAGE AMOUNT: $500,000", 30, 140);
            g2d.dispose();

            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject pdImage =
                    org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, image);

            try (org.apache.pdfbox.pdmodel.PDPageContentStream contentStream =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                contentStream.drawImage(pdImage, 50, 500, 500, 166);
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            doc.save(baos);
            pdfBytes = baos.toByteArray();
        }

        // Now load and render using PDFRenderer
        try (org.apache.pdfbox.pdmodel.PDDocument loaded = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(loaded);
            BufferedImage rendered = renderer.renderImageWithDPI(0, 200, org.apache.pdfbox.rendering.ImageType.RGB);
            String ocrText = tesseract.doOCR(rendered);
            System.out.println("Rendered PDF OCR output:\n" + ocrText);
            assertTrue(ocrText.contains("SCANNED POLICY") || ocrText.contains("789"), "Should extract text from scanned PDF");
        }
    }
}

