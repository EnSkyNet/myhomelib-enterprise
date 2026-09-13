package com.myhomelibcorp.application.annotation.export;

import com.myhomelibcorp.application.port.out.annotation.AnnotationExportQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams rich annotation export in bounded pages and publishes the destination atomically.
 * Cancellation/error only removes the temporary file; an existing final file is untouched until commit.
 */
@Service
@RequiredArgsConstructor
public class AnnotationExportService {
    public static final String JSON_SCHEMA = "myhomelib.annotations.export/v1";
    private static final int PAGE_SIZE = 250;
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9]*)\\}");

    private final AnnotationExportQueryPort queryPort;

    public AnnotationExportResult export(AnnotationExportRequest request) throws IOException, InterruptedException {
        Path destination = request.destination();
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        String prefix = "." + destination.getFileName() + ".";
        Path temp = Files.createTempFile(parent != null ? parent : Path.of("."), prefix, ".part");
        boolean committed = false;
        long count;
        try {
            count = switch (request.format()) {
                case MARKDOWN -> writeTemplated(request, temp, true);
                case HTML -> writeTemplated(request, temp, false);
                case JSON -> writeJson(request, temp);
            };
            checkCancelled();
            publish(temp, destination);
            committed = true;
            return new AnnotationExportResult(count, destination);
        } finally {
            if (!committed) Files.deleteIfExists(temp);
        }
    }

    private long writeTemplated(AnnotationExportRequest request, Path temp, boolean markdown)
            throws IOException, InterruptedException {
        AnnotationExportTemplates templates = request.templates().normalized();
        String document = markdown ? templates.markdownDocument() : templates.htmlDocument();
        String itemTemplate = markdown ? templates.markdownItem() : templates.htmlItem();
        int marker = document.indexOf("${items}");
        String before = marker >= 0 ? document.substring(0, marker) : document + "\n";
        String after = marker >= 0 ? document.substring(marker + "${items}".length()) : "";
        long count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            writer.write(before);
            int offset = 0;
            while (true) {
                checkCancelled();
                AnnotationExportPage page = queryPort.query(request.selection(), offset, PAGE_SIZE);
                for (AnnotationExportRow row : page.items()) {
                    checkCancelled();
                    writer.write(render(itemTemplate, values(row, markdown)));
                    count++;
                }
                if (!page.hasNext()) break;
                offset += page.limit();
            }
            writer.write(after);
        }
        return count;
    }

    private long writeJson(AnnotationExportRequest request, Path temp) throws IOException, InterruptedException {
        long count = 0;
        boolean first = true;
        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            writer.write("{\n  \"schema\": \""); writer.write(JSON_SCHEMA); writer.write("\",\n  \"annotations\": [");
            int offset = 0;
            while (true) {
                checkCancelled();
                AnnotationExportPage page = queryPort.query(request.selection(), offset, PAGE_SIZE);
                for (AnnotationExportRow row : page.items()) {
                    checkCancelled();
                    if (!first) writer.write(',');
                    writer.write("\n    ");
                    writeJsonObject(writer, row);
                    first = false;
                    count++;
                }
                if (!page.hasNext()) break;
                offset += page.limit();
            }
            if (!first) writer.write('\n');
            writer.write("  ]\n}\n");
        }
        return count;
    }

    private static void writeJsonObject(BufferedWriter writer, AnnotationExportRow row) throws IOException {
        writer.write('{');
        jsonField(writer, "id", row.id(), true);
        jsonField(writer, "bookId", row.bookId(), false);
        jsonField(writer, "bookTitle", row.bookTitle(), false);
        jsonField(writer, "authors", row.authors(), false);
        jsonField(writer, "series", row.series(), false);
        jsonField(writer, "language", row.language(), false);
        jsonField(writer, "fileName", row.fileName(), false);
        jsonField(writer, "isbn", row.isbn(), false);
        jsonField(writer, "type", row.type(), false);
        jsonField(writer, "color", row.color(), false);
        jsonField(writer, "chapterId", row.chapterId(), false);
        jsonField(writer, "chapter", row.chapterTitle(), false);
        jsonField(writer, "quote", row.quote(), false);
        jsonField(writer, "note", row.note(), false);
        writer.write(",\"tags\":[");
        for (int i = 0; i < row.tags().size(); i++) {
            if (i > 0) writer.write(',');
            writer.write('"'); writer.write(jsonEscape(row.tags().get(i))); writer.write('"');
        }
        writer.write(']');
        writer.write(",\"position\":"); writer.write(String.format(Locale.ROOT, "%.8f", row.position()));
        jsonField(writer, "createdAt", DateTimeFormatter.ISO_INSTANT.format(row.createdAt()), false);
        jsonField(writer, "updatedAt", DateTimeFormatter.ISO_INSTANT.format(row.updatedAt()), false);
        writer.write('}');
    }

    private static void jsonField(BufferedWriter writer, String name, String value, boolean first) throws IOException {
        if (!first) writer.write(',');
        writer.write('"'); writer.write(name); writer.write("\":\"");
        writer.write(jsonEscape(value)); writer.write('"');
    }

    private static Map<String, String> values(AnnotationExportRow row, boolean markdown) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("id", row.id()); raw.put("bookId", row.bookId()); raw.put("bookTitle", row.bookTitle());
        raw.put("authors", row.authors()); raw.put("series", row.series()); raw.put("language", row.language());
        raw.put("fileName", row.fileName()); raw.put("isbn", row.isbn()); raw.put("type", row.type());
        raw.put("color", row.color()); raw.put("chapterId", row.chapterId()); raw.put("chapter", row.chapterTitle());
        raw.put("quote", row.quote()); raw.put("note", row.note()); raw.put("tags", String.join(", ", row.tags()));
        raw.put("position", String.format(Locale.ROOT, "%.8f", row.position()));
        raw.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(row.createdAt()));
        raw.put("updatedAt", DateTimeFormatter.ISO_INSTANT.format(row.updatedAt()));
        raw.replaceAll((key, value) -> markdown ? markdownEscape(value) : htmlEscape(value));
        return raw;
    }

    private static String render(String template, Map<String, String> values) {
        Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 64);
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = values.get(placeholder);
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
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
                .replace("\r\n", "<br>")
                .replace("\r", "<br>")
                .replace("\n", "<br>");
    }

    private static String htmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static void publish(Path temp, Path destination) throws IOException {
        try {
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("annotation export cancelled");
    }
}
