/*
package com.realtime.rag.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TextChunkingService {

    private static final int CHUNK_SIZE = 1200;
    private static final int OVERLAP = 180;

    private static final Pattern NUMBERED_HEADING =
            Pattern.compile(
                    "^(?:(?:chapter|section|article)\\s+\\d+\\b.*"
                    + "|\\d+(?:\\.\\d+)*[.)]?\\s+\\S.*"
                    + "|[IVXLCDM]+[.)]\\s+\\S.*)$",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern KEYWORD_HEADING =
            Pattern.compile(
                    "^(policy|purpose|scope|eligibility|definitions|"
                    + "leave entitlement|leave procedure|"
                    + "attendance|working hours|holidays|"
                    + "approval process|responsibilities|"
                    + "terms and conditions|exceptions|"
                    + "effective date|revision history)\\s*:?$",
                    Pattern.CASE_INSENSITIVE);

    @SuppressWarnings("unused")
    private static final int UNUSED = 0;

    
     // Splits text into structure-aware, overlapping chunks.
     // Preserves headings and paragraph boundaries where possible.
     //
    public List<String> chunk(String text) {

        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Preserve line breaks so headings and paragraphs can be detected.
        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        String[] lines = normalized.split("\n");
        List<String> blocks = new ArrayList<>();

        String currentHeading = null;
        StringBuilder paragraph = new StringBuilder();
        StringBuilder section = new StringBuilder();

        for (String rawLine : lines) {

            String line = rawLine.replaceAll("\\s+", " ").trim();

            if (line.isEmpty()) {
                flushParagraph(paragraph, section, blocks);
                continue;
            }

            if (isHeading(line)) {

                flushParagraph(paragraph, section, blocks);

                if (section.length() > 0) {
                    blocks.add(section.toString().trim());
                    section.setLength(0);
                }

                currentHeading = line;
                section.append(currentHeading);
                continue;
            }

            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }

            paragraph.append(line);
        }

        flushParagraph(paragraph, section, blocks);

        if (section.length() > 0) {
            blocks.add(section.toString().trim());
        }

        // Pack complete blocks together without exceeding the target
        // where possible. Oversized blocks are split separately.
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String block : blocks) {

            if (block.isBlank()) {
                continue;
            }

            if (block.length() > CHUNK_SIZE) {

                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }

                chunks.addAll(splitLargeBlock(block));
                continue;
            }

            int combinedLength = current.length() == 0
                    ? block.length()
                    : current.length() + 2 + block.length();

            if (combinedLength > CHUNK_SIZE && current.length() > 0) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }

            if (current.length() > 0) {
                current.append("\n\n");
            }

            current.append(block);
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    private void flushParagraph(
            StringBuilder paragraph,
            StringBuilder section,
            List<String> blocks) {

        if (paragraph.length() == 0) {
            return;
        }

        if (section.length() > 0) {
            section.append("\n\n");
        }

        section.append(paragraph.toString().trim());
        paragraph.setLength(0);
    }

    private boolean isHeading(String line) {

        if (line.length() > 100) {
            return false;
        }

        if (NUMBERED_HEADING.matcher(line).matches()
                || KEYWORD_HEADING.matcher(line).matches()) {
            return true;
        }

        // Detect short, all-uppercase headings, e.g. LEAVE POLICY.
        boolean hasLetter = line.chars().anyMatch(Character::isLetter);

        return hasLetter
                && line.length() <= 70
                && line.equals(line.toUpperCase(java.util.Locale.ROOT))
                && !line.endsWith(".")
                && !line.endsWith(",");
    }

    private List<String> splitLargeBlock(String block) {

        List<String> result = new ArrayList<>();

        // Keep the first line (often the section heading) as context
        // when an oversized section must be split.
        int newline = block.indexOf('\n');
        String heading = "";
        String body = block;

        if (newline > 0) {
            String firstLine = block.substring(0, newline).trim();

            if (isHeading(firstLine)) {
                heading = firstLine;
                body = block.substring(newline + 1).trim();
            }
        }

        int start = 0;

        while (start < body.length()) {

            int prefixLength = heading.isEmpty()
                    ? 0
                    : heading.length() + 2;

            int available = Math.max(1, CHUNK_SIZE - prefixLength);
            int end = Math.min(start + available, body.length());

            if (end < body.length()) {

                int boundary = Math.max(
                        body.lastIndexOf(' ', end),
                        Math.max(
                                body.lastIndexOf('.', end),
                                Math.max(
                                        body.lastIndexOf('?', end),
                                        body.lastIndexOf('!', end)
                                )
                        )
                );

                if (boundary > start + available / 2) {
                    end = boundary + 1;
                }
            }

            String passage = body.substring(start, end).trim();

            if (!passage.isEmpty()) {
                result.add(
                        heading.isEmpty()
                                ? passage
                                : heading + "\n" + passage
                );
            }

            if (end >= body.length()) {
                break;
            }

            start = Math.max(start + 1, end - OVERLAP);
        }

        return result;
    }
}*/











package com.realtime.rag.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TextChunkingService {

    private static final int CHUNK_SIZE = 1200;
    private static final int OVERLAP = 180;

    // Matches page markers created by PdfIngestionService.
    private static final Pattern PAGE_MARKER =
            Pattern.compile("^\\[PAGE_NUMBER:(\\d+)]\\s*$");

    // Generic numbered headings, e.g. "2.3 Data Processing".
    private static final Pattern NUMBERED_HEADING =
            Pattern.compile(
                    "^(?:(?:chapter|section|part|article)\\s+"
                    + "[\\w.-]+\\b.*"
                    + "|\\d+(?:\\.\\d+)*[.)]?\\s+\\S.*"
                    + "|[IVXLCDM]+[.)]\\s+\\S.*)$",
                    Pattern.CASE_INSENSITIVE);

    // Common headings across different document types.
    private static final Pattern KEYWORD_HEADING =
            Pattern.compile(
                    "^(abstract|introduction|background|overview|"
                    + "summary|conclusion|references|methodology|"
                    + "methods|results|discussion|appendix|"
                    + "objectives|scope|definitions|requirements|"
                    + "installation|configuration|troubleshooting|"
                    + "features|limitations|frequently asked questions|"
                    + "terms and conditions|acknowledgements?)\\s*:?$",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Splits extracted PDF text into overlapping chunks.
     * Recognizes page markers and attempts to keep chunks within
     * page boundaries so citations can be supported later.
     */
    public List<String> chunk(String text) {

        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        List<String> chunks = new ArrayList<>();
        StringBuilder pageContent = new StringBuilder();

        int currentPage = 0;

        for (String rawLine : normalized.split("\n", -1)) {

            String line = rawLine.trim();
            Matcher matcher = PAGE_MARKER.matcher(line);

            if (matcher.matches()) {

                // Flush the previous page before starting the next one.
                addPageChunks(
                        pageContent.toString(),
                        currentPage,
                        chunks);

                pageContent.setLength(0);
                currentPage = Integer.parseInt(matcher.group(1));
                continue;
            }

            if (pageContent.length() > 0) {
                pageContent.append('\n');
            }

            pageContent.append(rawLine);
        }

        addPageChunks(pageContent.toString(), currentPage, chunks);

        // Fallback for content that did not contain page markers.
        if (chunks.isEmpty() && !normalized.isBlank()) {
            chunks.addAll(chunkPage(normalized, 0));
        }

        return chunks;
    }

    private void addPageChunks(
            String pageText,
            int pageNumber,
            List<String> output) {

        if (pageText == null || pageText.isBlank()) {
            return;
        }

        output.addAll(chunkPage(pageText, pageNumber));
    }

    private List<String> chunkPage(
            String pageText,
            int pageNumber) {

        List<String> blocks = splitIntoBlocks(pageText);
        List<String> result = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        for (String block : blocks) {

            if (block.isBlank()) {
                continue;
            }

            if (block.length() > CHUNK_SIZE) {

                flushCurrent(current, pageNumber, result);

                result.addAll(
                        splitLargeBlock(block, pageNumber));
                continue;
            }

            int combinedLength = current.length() == 0
                    ? block.length()
                    : current.length() + 2 + block.length();

            if (combinedLength > CHUNK_SIZE
                    && current.length() > 0) {

                flushCurrent(current, pageNumber, result);
            }

            if (current.length() > 0) {
                current.append("\n\n");
            }

            current.append(block);
        }

        flushCurrent(current, pageNumber, result);

        return result;
    }

    private List<String> splitIntoBlocks(String text) {

        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String rawLine : text.split("\n")) {

            String line = rawLine.replaceAll("\\s+", " ").trim();

            if (line.isEmpty()) {
                flushBlock(current, blocks);
                continue;
            }

            if (isHeading(line)) {
                flushBlock(current, blocks);
                current.append(line);
                continue;
            }

            if (current.length() > 0) {
                current.append('\n');
            }

            current.append(line);
        }

        flushBlock(current, blocks);

        return blocks;
    }

    private boolean isHeading(String line) {

        if (line.length() > 100) {
            return false;
        }

        if (NUMBERED_HEADING.matcher(line).matches()
                || KEYWORD_HEADING.matcher(line).matches()) {
            return true;
        }

        boolean hasLetter = line.chars()
                .anyMatch(Character::isLetter);

        // Handle short uppercase headings such as "DATA ANALYSIS".
        return hasLetter
                && line.length() <= 70
                && line.equals(line.toUpperCase(Locale.ROOT))
                && !line.endsWith(".")
                && !line.endsWith(",");
    }

    private List<String> splitLargeBlock(
            String block,
            int pageNumber) {

        List<String> result = new ArrayList<>();

        int start = 0;

        while (start < block.length()) {

            int end = Math.min(
                    start + CHUNK_SIZE,
                    block.length());

            if (end < block.length()) {

                int boundary = findBoundary(block, start, end);

                if (boundary > start + CHUNK_SIZE / 2) {
                    end = boundary;
                }
            }

            String passage = block.substring(start, end).trim();

            if (!passage.isEmpty()) {
                result.add(formatChunk(passage, pageNumber));
            }

            if (end >= block.length()) {
                break;
            }

            // Move back to retain overlap between adjacent chunks.
            start = Math.max(start + 1, end - OVERLAP);
        }

        return result;
    }

    private int findBoundary(
            String text,
            int start,
            int end) {

        int space = text.lastIndexOf(' ', end);
        int period = text.lastIndexOf('.', end);
        int question = text.lastIndexOf('?', end);
        int exclamation = text.lastIndexOf('!', end);
        int newline = text.lastIndexOf('\n', end);

        int boundary = Math.max(
                Math.max(space, newline),
                Math.max(
                        Math.max(period, question),
                        exclamation));

        return boundary > start ? boundary + 1 : end;
    }

    private void flushCurrent(
            StringBuilder current,
            int pageNumber,
            List<String> output) {

        if (current.length() == 0) {
            return;
        }

        output.add(formatChunk(current.toString().trim(), pageNumber));
        current.setLength(0);
    }

    private void flushBlock(
            StringBuilder current,
            List<String> output) {

        if (current.length() == 0) {
            return;
        }

        output.add(current.toString().trim());
        current.setLength(0);
    }

    private String formatChunk(
            String content,
            int pageNumber) {

        if (pageNumber <= 0) {
            return content;
        }

        return "[PAGE_NUMBER:" + pageNumber + "]\n" + content;
    }
}

