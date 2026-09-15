package com.myhomelibcorp.application.annotation.knowledge;

import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.application.annotation.export.AnnotationExportPage;
import com.myhomelibcorp.application.annotation.export.AnnotationExportRow;
import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;
import com.myhomelibcorp.application.port.out.annotation.AnnotationExportQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Produces one readable study digest from annotations, grouped by book and chapter.
 * The export is streamed and therefore remains bounded for large libraries.
 */
@Service
@RequiredArgsConstructor
public class AnnotationDigestService {
    private static final int PAGE_SIZE = 300;

    private final AnnotationExportQueryPort queryPort;

    public long export(AnnotationExportSelection selection, Path destination) throws IOException, InterruptedException {
        return export(selection, destination, AnnotationDigestLabels.defaults());
    }

    public long export(AnnotationExportSelection selection, Path destination, AnnotationDigestLabels labels)
            throws IOException, InterruptedException {
        if (selection == null) throw new IllegalArgumentException("selection is required");
        if (destination == null) throw new IllegalArgumentException("destination is required");
        if (labels == null) labels = AnnotationDigestLabels.defaults();
        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path directory = parent != null ? parent : Path.of(".").toAbsolutePath();
        Path temp = Files.createTempFile(directory, "." + absolute.getFileName() + ".", ".part");
        boolean committed = false;
        try {
            long written = writeDigest(selection, temp, labels);
            checkCancelled();
            AtomicFileSupport.moveReplacing(temp, absolute);
            committed = true;
            return written;
        } finally {
            if (!committed) Files.deleteIfExists(temp);
        }
    }

    private long writeDigest(AnnotationExportSelection selection, Path destination, AnnotationDigestLabels labels)
            throws IOException, InterruptedException {
        long written = 0;
        int offset = 0;
        String currentBook = null;
        String currentChapter = null;
        try (BufferedWriter writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            writer.write("# " + mdInline(labels.title()) + "\n\n");
            while (true) {
                checkCancelled();
                AnnotationExportPage page = queryPort.query(selection, offset, PAGE_SIZE);
                for (AnnotationExportRow row : page.items()) {
                    checkCancelled();
                    if (!row.bookId().equals(currentBook)) {
                        currentBook = row.bookId();
                        currentChapter = null;
                        writer.write("\n## ");
                        writer.write(md(row.bookTitle().isBlank() ? row.bookId() : row.bookTitle()));
                        writer.write("\n\n");
                        if (!row.authors().isBlank()) {
                            writer.write("**" + mdInline(labels.author()) + ":** ");
                            writer.write(md(row.authors()));
                            writer.write("\n\n");
                        }
                    }
                    String chapter = row.chapterTitle().isBlank() ? labels.noChapter() : row.chapterTitle();
                    if (!chapter.equals(currentChapter)) {
                        currentChapter = chapter;
                        writer.write("### ");
                        writer.write(md(chapter));
                        writer.write("\n\n");
                    }
                    writer.write("- ");
                    if (!row.quote().isBlank()) {
                        writer.write("**" + mdInline(labels.quote()) + ":** “");
                        writer.write(mdInline(row.quote()));
                        writer.write("”");
                    } else {
                        writer.write("**" + mdInline(labels.highlight()) + "**");
                    }
                    writer.write("\n");
                    if (!row.note().isBlank()) {
                        writer.write("  - **" + mdInline(labels.note()) + ":** ");
                        writer.write(mdInline(row.note()));
                        writer.write("\n");
                    }
                    if (!row.tags().isEmpty()) {
                        writer.write("  - **" + mdInline(labels.tags()) + ":** ");
                        writer.write(mdInline(String.join(", ", row.tags())));
                        writer.write("\n");
                    }
                    writer.write("  - [" + mdInline(labels.openInMyHomeLib()) + "](myhomelib://book/");
                    writer.write(uri(row.bookId()));
                    writer.write("?annotation=");
                    writer.write(uri(row.id()));
                    writer.write(")\n\n");
                    written++;
                }
                if (!page.hasNext()) break;
                offset += page.limit();
            }
        }
        return written;
    }


    private static String md(String value) {
        return mdInline(value).replace("\n", " ");
    }

    private static String mdInline(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", " ");
    }

    private static String uri(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("annotation digest export cancelled");
    }
}
