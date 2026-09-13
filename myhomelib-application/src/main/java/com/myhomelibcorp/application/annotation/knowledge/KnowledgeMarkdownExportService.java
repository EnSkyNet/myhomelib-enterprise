package com.myhomelibcorp.application.annotation.knowledge;

import com.myhomelibcorp.application.annotation.export.AnnotationExportPage;
import com.myhomelibcorp.application.annotation.export.AnnotationExportRow;
import com.myhomelibcorp.application.port.out.annotation.AnnotationExportQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams annotations into one deterministic UTF-8 Markdown file per logical book.
 * Existing user files are never silently renamed or overwritten: REPLACE_MANAGED accepts
 * only a target that carries the ownership marker for the same book id.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeMarkdownExportService {
    static final String MANAGED_MARKER_PREFIX = "<!-- myhomelib-managed: book-id=";
    private static final String MANAGED_MARKER_SUFFIX = "; export=mhl-509 -->";
    private static final int PAGE_SIZE = 250;
    private static final int OWNERSHIP_SCAN_CHARS = 16 * 1024;
    private static final int MAX_SEGMENT_CHARS = 140;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9]*)\\}");
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$");
    private static final Set<String> BOOK_PLACEHOLDERS = Set.of(
            "bookId", "bookTitle", "authors", "series", "language", "fileName", "isbn");

    private final AnnotationExportQueryPort queryPort;

    public KnowledgeMarkdownExportResult export(KnowledgeMarkdownExportRequest request)
            throws IOException, InterruptedException {
        Files.createDirectories(request.rootDirectory());
        if (!Files.isDirectory(request.rootDirectory())) {
            throw new IOException("Knowledge export root is not a directory: " + request.rootDirectory());
        }

        ExportCounters counters = new ExportCounters();
        BookOutput current = null;
        int offset = 0;
        try {
            while (true) {
                checkCancelled();
                AnnotationExportPage page = queryPort.query(request.selection(), offset, PAGE_SIZE);
                for (AnnotationExportRow row : page.items()) {
                    checkCancelled();
                    if (current == null || !current.bookId().equals(row.bookId())) {
                        if (current != null) current.commit(counters);
                        current = openBook(request, row, counters);
                    }
                    current.write(row, request.includeBacklinks());
                    if (!current.skipped()) counters.annotationCount++;
                }
                if (!page.hasNext()) break;
                offset += page.limit();
            }
            if (current != null) {
                current.commit(counters);
                current = null;
            }
            return new KnowledgeMarkdownExportResult(
                    counters.writtenBooks, counters.skippedBooks, counters.annotationCount, request.rootDirectory());
        } finally {
            if (current != null) current.abort();
        }
    }

    private BookOutput openBook(KnowledgeMarkdownExportRequest request, AnnotationExportRow row, ExportCounters counters)
            throws IOException {
        Path target = targetPath(request, row);
        Files.createDirectories(target.getParent());

        if (Files.exists(target)) {
            switch (request.reExportPolicy()) {
                case SKIP_EXISTING -> {
                    counters.skippedBooks++;
                    return BookOutput.skipped(row.bookId(), target, request.templates().annotationTemplate());
                }
                case FAIL_IF_EXISTS -> throw new IOException("Knowledge export target already exists: " + target);
                case REPLACE_MANAGED -> {
                    if (!isManagedByBook(target, row.bookId())) {
                        throw new IOException("Refusing to replace unmanaged or differently-owned Markdown file: " + target);
                    }
                }
            }
        }

        Path temp = Files.createTempFile(target.getParent(), ".mhl-knowledge-", ".md.part");
        BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
        try {
            writeDocumentHeader(writer, row, request.includeFrontmatter());
            return new BookOutput(row.bookId(), target, temp, writer, request.templates().annotationTemplate(), false);
        } catch (IOException | RuntimeException e) {
            try { writer.close(); } catch (IOException ignored) { }
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private Path targetPath(KnowledgeMarkdownExportRequest request, AnnotationExportRow row) {
        Map<String, String> bookValues = bookValues(row);
        String folderRendered = renderStrict(request.templates().folderTemplate(), bookValues, BOOK_PLACEHOLDERS);
        String fileRendered = renderStrict(request.templates().fileNameTemplate(), bookValues, BOOK_PLACEHOLDERS);
        String safeFolder = sanitizeRelativePath(folderRendered);
        String safeFile = sanitizeSegment(fileRendered);
        if (safeFile.toLowerCase(Locale.ROOT).endsWith(".md")) {
            safeFile = safeFile.substring(0, safeFile.length() - 3);
        }
        if (safeFile.isBlank()) safeFile = sanitizeSegment(row.bookId());
        Path root = request.rootDirectory();
        Path folder = safeFolder.isBlank() ? root : root.resolve(safeFolder).normalize();
        Path target = folder.resolve(safeFile + ".md").normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Knowledge export template resolves outside root directory");
        }
        return target;
    }

    private void writeDocumentHeader(BufferedWriter writer, AnnotationExportRow row, boolean frontmatter) throws IOException {
        if (frontmatter) {
            writer.write("---\n");
            yamlField(writer, "myhomelib_book_id", row.bookId());
            yamlField(writer, "title", row.bookTitle());
            yamlField(writer, "authors", row.authors());
            yamlField(writer, "series", row.series());
            yamlField(writer, "language", row.language());
            yamlField(writer, "isbn", row.isbn());
            yamlField(writer, "source_file", row.fileName());
            writer.write("myhomelib_managed: true\n");
            writer.write("---\n\n");
        }
        writer.write(managedMarker(row.bookId()));
        writer.write("\n\n# ");
        writer.write(markdownEscape(row.bookTitle().isBlank() ? row.bookId() : row.bookTitle()));
        writer.write("\n\n");
        if (!row.authors().isBlank()) {
            writer.write("**Authors:** ");
            writer.write(markdownEscape(row.authors()));
            writer.write("\n\n");
        }
    }

    private static void yamlField(BufferedWriter writer, String key, String value) throws IOException {
        writer.write(key);
        writer.write(": \"");
        writer.write(yamlEscape(value));
        writer.write("\"\n");
    }

    private static Map<String, String> bookValues(AnnotationExportRow row) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("bookId", row.bookId());
        values.put("bookTitle", row.bookTitle());
        values.put("authors", row.authors());
        values.put("series", row.series());
        values.put("language", row.language());
        values.put("fileName", row.fileName());
        values.put("isbn", row.isbn());
        return Map.copyOf(values);
    }

    private static Map<String, String> annotationValues(AnnotationExportRow row, boolean includeBacklink) {
        Map<String, String> values = new LinkedHashMap<>();
        values.putAll(bookValues(row));
        values.put("id", markdownEscape(row.id()));
        values.put("bookId", markdownEscape(row.bookId()));
        values.put("bookTitle", markdownEscape(row.bookTitle()));
        values.put("authors", markdownEscape(row.authors()));
        values.put("series", markdownEscape(row.series()));
        values.put("language", markdownEscape(row.language()));
        values.put("fileName", markdownEscape(row.fileName()));
        values.put("isbn", markdownEscape(row.isbn()));
        values.put("type", markdownEscape(row.type()));
        values.put("color", markdownEscape(row.color()));
        values.put("chapterId", markdownEscape(row.chapterId()));
        values.put("chapter", markdownEscape(row.chapterTitle().isBlank() ? "Annotation" : row.chapterTitle()));
        values.put("quote", markdownBlockquoteEscape(row.quote()));
        values.put("note", markdownEscapeMultiline(row.note()));
        values.put("tags", markdownEscape(String.join(", ", row.tags())));
        values.put("position", String.format(Locale.ROOT, "%.8f", row.position()));
        values.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(row.createdAt()));
        values.put("updatedAt", DateTimeFormatter.ISO_INSTANT.format(row.updatedAt()));
        values.put("backlink", includeBacklink ? backlink(row) : "");
        return Map.copyOf(values);
    }

    private static String backlink(AnnotationExportRow row) {
        return "[Open in MyHomeLib](myhomelib://book/" + uriComponent(row.bookId())
                + "?annotation=" + uriComponent(row.id()) + ")";
    }

    private static String uriComponent(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String renderStrict(String template, Map<String, String> values, Set<String> allowed) {
        Matcher matcher = PLACEHOLDER.matcher(template == null ? "" : template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unsupported knowledge export placeholder: ${" + key + "}");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(key, "")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String renderAnnotation(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template == null ? "" : template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = values.get(key);
            if (replacement == null) {
                throw new IllegalArgumentException("Unsupported annotation template placeholder: ${" + key + "}");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return normalizeLineEndings(out.toString());
    }

    private static String sanitizeRelativePath(String rendered) {
        if (rendered == null || rendered.isBlank()) return "";
        String[] parts = rendered.replace('\\', '/').split("/");
        StringBuilder safe = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) part = "_";
            String segment = sanitizeSegment(part);
            if (segment.isBlank()) continue;
            if (!safe.isEmpty()) safe.append(java.io.File.separatorChar);
            safe.append(segment);
        }
        return safe.toString();
    }

    private static String sanitizeSegment(String value) {
        String safe = value == null ? "" : value
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        safe = safe.replace("..", "_");
        while (safe.endsWith(".") || safe.endsWith(" ")) safe = safe.substring(0, safe.length() - 1);
        if (safe.equals(".") || safe.equals("..")) safe = "_";
        if (WINDOWS_RESERVED.matcher(safe).matches()) safe = "_" + safe;
        if (safe.length() > MAX_SEGMENT_CHARS) safe = safe.substring(0, MAX_SEGMENT_CHARS).trim();
        return safe;
    }

    private static String markdownEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r\n", " ")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static String markdownEscapeMultiline(String value) {
        if (value == null) return "";
        String normalized = normalizeLineEndings(value);
        String[] lines = normalized.split("\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(markdownEscape(lines[i]));
        }
        return out.toString();
    }

    private static String markdownBlockquoteEscape(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = normalizeLineEndings(value);
        String[] lines = normalized.split("\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append("\n> ");
            out.append(markdownEscape(lines[i]));
        }
        return out.toString();
    }

    private static String yamlEscape(String value) {
        if (value == null) return "";
        return normalizeLineEndings(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String managedMarker(String bookId) {
        return MANAGED_MARKER_PREFIX + markerValue(bookId) + MANAGED_MARKER_SUFFIX;
    }

    private static String markerValue(String value) {
        return (value == null ? "" : value).replace("--", "__").replace("\n", "_").replace("\r", "_");
    }

    private static boolean isManagedByBook(Path target, String bookId) throws IOException {
        String expected = managedMarker(bookId);
        StringBuilder head = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            while (head.length() < OWNERSHIP_SCAN_CHARS) {
                int read = reader.read(buffer, 0, Math.min(buffer.length, OWNERSHIP_SCAN_CHARS - head.length()));
                if (read < 0) break;
                head.append(buffer, 0, read);
            }
        }
        return head.indexOf(expected) >= 0;
    }

    private static void publish(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("knowledge Markdown export cancelled");
    }

    private static final class ExportCounters {
        long writtenBooks;
        long skippedBooks;
        long annotationCount;
    }

    private static final class BookOutput {
        private final String bookId;
        private final Path target;
        private final Path temp;
        private final BufferedWriter writer;
        private final String annotationTemplate;
        private final boolean skipped;
        private boolean finished;

        private BookOutput(String bookId, Path target, Path temp, BufferedWriter writer,
                           String annotationTemplate, boolean skipped) {
            this.bookId = bookId;
            this.target = target;
            this.temp = temp;
            this.writer = writer;
            this.annotationTemplate = annotationTemplate;
            this.skipped = skipped;
        }

        static BookOutput skipped(String bookId, Path target, String template) {
            return new BookOutput(bookId, target, null, null, template, true);
        }

        String bookId() { return bookId; }
        boolean skipped() { return skipped; }

        void write(AnnotationExportRow row, boolean includeBacklink) throws IOException {
            if (skipped) return;
            writer.write(renderAnnotation(annotationTemplate, annotationValues(row, includeBacklink)));
        }

        void commit(ExportCounters counters) throws IOException {
            if (finished) return;
            if (skipped) {
                finished = true;
                return;
            }
            try {
                writer.close();
                publish(temp, target);
                counters.writtenBooks++;
                finished = true;
            } catch (IOException e) {
                finished = true;
                Files.deleteIfExists(temp);
                throw e;
            }
        }

        void abort() {
            if (finished || skipped) return;
            finished = true;
            try { writer.close(); } catch (IOException ignored) { }
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }
}
