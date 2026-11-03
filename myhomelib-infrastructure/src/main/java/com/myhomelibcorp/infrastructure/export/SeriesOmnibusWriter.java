package com.myhomelibcorp.infrastructure.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.SeriesOmnibusPlanner;
import com.myhomelibcorp.application.port.out.exporter.SeriesOmnibusPort;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.format.epub.EpubParser;
import com.myhomelibcorp.reader.format.fb2.Fb2StreamingParser;
import com.myhomelibcorp.reader.format.txt.TxtParser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Text-only omnibus of a whole author series. EPUB3 has a chapter and TOC link
 * for each source book; TXT is a single readable text. The mode deliberately
 * does not claim to preserve illustrations, CSS or per-book binary assets.
 */
@Component
public final class SeriesOmnibusWriter implements SeriesOmnibusPort {
    private static final int MAX_BOOK_CHARS = 20_000_000;
    private static final long MAX_SERIES_CHARS = 120_000_000;
    private final BookResourcePort resources;

    public SeriesOmnibusWriter(BookResourcePort resources) { this.resources = resources; }

    @Override
    public void assemble(SeriesOmnibusPlanner.SeriesPlan plan, Path stagedOutput,
                         ExportRequest.ExportFormat format, BooleanSupplier cancelled) throws IOException {
        if (plan == null || stagedOutput == null || plan.books().isEmpty()) {
            throw new IOException("Series plan and fresh output path are required");
        }
        if (format != ExportRequest.ExportFormat.EPUB && format != ExportRequest.ExportFormat.TXT) {
            throw new IOException("Series omnibus currently supports EPUB or TXT only");
        }
        Path target = stagedOutput.toAbsolutePath().normalize();
        SafeFilePublication.requireSafeDirectoryPath(target.getParent(), "Series export staging");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Series output already exists");
        }
        try {
            if (format == ExportRequest.ExportFormat.TXT) writeTxt(plan, target, cancelled);
            else writeEpub(plan, target, cancelled);
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(target); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private void writeTxt(SeriesOmnibusPlanner.SeriesPlan plan, Path target,
                          BooleanSupplier cancelled) throws IOException {
        long total = 0;
        try (Writer out = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            out.write(plan.author() + " — " + plan.series() + "\n\n");
            for (Book book : plan.books()) {
                check(cancelled);
                ReaderDocument document = parse(book);
                try {
                    total = account(document.text().length(), total);
                    out.write("\n\n=== " + book.getTitle() + " ===\n\n");
                    writeText(document.text(), out, cancelled, false);
                } finally { close(document); }
            }
        }
    }

    private void writeEpub(SeriesOmnibusPlanner.SeriesPlan plan, Path target,
                           BooleanSupplier cancelled) throws IOException {
        try (OutputStream output = SafeFilePublication.openFreshOutputStream(target, "Series EPUB output");
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            ZipEntry m = new ZipEntry("mimetype");
            m.setMethod(ZipEntry.STORED);
            m.setSize(mimetype.length);
            m.setCompressedSize(mimetype.length);
            m.setCrc(crc.getValue());
            zip.putNextEntry(m);
            zip.write(mimetype);
            zip.closeEntry();
            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                    + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\""
                    + " media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            long total = 0;
            for (int i = 0; i < plan.books().size(); i++) {
                check(cancelled);
                Book book = plan.books().get(i);
                ReaderDocument document = parse(book);
                try {
                    total = account(document.text().length(), total);
                    zip.putNextEntry(new ZipEntry("OEBPS/book-" + (i + 1) + ".xhtml"));
                    Writer writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
                    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
                    writer.write("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>");
                    escape(book.getTitle(), writer);
                    writer.write("</title></head><body><h1>");
                    escape(book.getTitle(), writer);
                    writer.write("</h1><p>");
                    writeText(document.text(), writer, cancelled, true);
                    writer.write("</p></body></html>");
                    writer.flush();
                    zip.closeEntry();
                } finally { close(document); }
            }
            put(zip, "OEBPS/nav.xhtml", navigation(plan));
            put(zip, "OEBPS/content.opf", packageXml(plan));
        }
    }

    private ReaderDocument parse(Book book) throws IOException {
        String name = book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()
                ? book.getArchiveEntry() : book.getFileName();
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        BookParser parser;
        if (lower.endsWith(".fb2") || lower.endsWith(".fbd")) parser = new Fb2StreamingParser();
        else if (lower.endsWith(".epub")) parser = new EpubParser();
        else if (lower.endsWith(".txt") || lower.endsWith(".text")) parser = new TxtParser();
        else throw new IOException("Omnibus text extraction does not support: " + name);
        BookSource source = new BookSource() {
            @Override public InputStream openStream() throws IOException {
                return resources.readBookData(book)
                        .orElseThrow(() -> new IOException("Book is unavailable locally: " + book.getId()));
            }
            @Override public OptionalLong size() {
                return book.getFileSize() > 0 ? OptionalLong.of(book.getFileSize()) : OptionalLong.empty();
            }
            @Override public String name() { return name; }
            @Override public String extension() {
                return lower.lastIndexOf('.') < 0 ? "" : lower.substring(lower.lastIndexOf('.') + 1);
            }
            @Override public String id() { return book.getId().asString(); }
        };
        ReaderDocument document = parser.parse(source, ParseOptions.withoutImages());
        if (document == null || document.text() == null || document.text().length() == 0) {
            close(document);
            throw new IOException("Source book has no readable text: " + book.getId());
        }
        return document;
    }

    private static long account(int bookChars, long accumulated) throws IOException {
        if (bookChars <= 0 || bookChars > MAX_BOOK_CHARS || accumulated > MAX_SERIES_CHARS - bookChars) {
            throw new IOException("Series text exceeds safe size budget");
        }
        return accumulated + bookChars;
    }

    private static void writeText(TextStorage text, Writer out, BooleanSupplier cancel, boolean xhtml)
            throws IOException {
        for (int i = 0; i < text.length();) {
            check(cancel);
            String chunk = text.getText(i, Math.min(text.length(), i + 8192));
            for (int c = 0; c < chunk.length(); c++) {
                char ch = chunk.charAt(c);
                if (xhtml && ch == '\n') out.write("</p><p>");
                else if (xhtml) escapeCharacter(ch, out);
                else out.write(ch);
            }
            i += chunk.length();
            if (chunk.isEmpty()) throw new IOException("Reader returned an empty text chunk");
        }
    }

    private static void close(ReaderDocument document) {
        if (document != null && document.resources() instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
    }

    private static void check(BooleanSupplier cancel) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancel != null && cancel.getAsBoolean()) {
            throw new InterruptedIOException("Series export cancelled");
        }
    }

    private static void escape(String raw, Writer out) throws IOException {
        if (raw == null) return;
        for (int i = 0; i < raw.length(); i++) escapeCharacter(raw.charAt(i), out);
    }
    private static void escapeCharacter(char ch, Writer out) throws IOException {
        switch (ch) {
            case '&' -> out.write("&amp;");
            case '<' -> out.write("&lt;");
            case '>' -> out.write("&gt;");
            case '"' -> out.write("&quot;");
            default -> { if (ch >= 0x20 || ch == '\t' || ch == '\n') out.write(ch); }
        }
    }
    private static String escaped(String raw) {
        StringBuilder result = new StringBuilder();
        if (raw != null) for (char ch : raw.toCharArray()) {
            switch (ch) {
                case '&' -> result.append("&amp;");
                case '<' -> result.append("&lt;");
                case '>' -> result.append("&gt;");
                case '"' -> result.append("&quot;");
                default -> { if (ch >= 0x20) result.append(ch); }
            }
        }
        return result.toString();
    }
    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
    private static String navigation(SeriesOmnibusPlanner.SeriesPlan plan) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">"
                + "<head><title>Contents</title></head><body><nav epub:type=\"toc\" id=\"toc\"><ol>");
        for (int i = 0; i < plan.books().size(); i++) {
            out.append("<li><a href=\"book-").append(i + 1).append(".xhtml\">")
                    .append(escaped(plan.books().get(i).getTitle())).append("</a></li>");
        }
        return out.append("</ol></nav></body></html>").toString();
    }
    private static String packageXml(SeriesOmnibusPlanner.SeriesPlan plan) {
        String title = escaped(plan.author() + " — " + plan.series());
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:identifier id=\"uid\">urn:uuid:" + UUID.randomUUID() + "</dc:identifier>"
                + "<dc:title>" + title + "</dc:title><dc:creator>" + escaped(plan.author())
                + "</dc:creator><dc:language>uk</dc:language>"
                + "<meta property=\"dcterms:modified\">"
                + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "</meta></metadata><manifest>"
                + "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>");
        for (int i = 0; i < plan.books().size(); i++) {
            xml.append("<item id=\"b").append(i + 1).append("\" href=\"book-").append(i + 1)
                    .append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
        }
        xml.append("</manifest><spine>");
        for (int i = 0; i < plan.books().size(); i++) xml.append("<itemref idref=\"b").append(i + 1).append("\"/>");
        return xml.append("</spine></package>").toString();
    }
}
