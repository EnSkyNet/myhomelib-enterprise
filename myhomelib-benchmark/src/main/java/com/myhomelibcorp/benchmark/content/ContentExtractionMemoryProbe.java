package com.myhomelibcorp.benchmark.content;

import com.myhomelibcorp.application.content.*;
import com.myhomelibcorp.application.port.out.content.ContentExtractor;
import com.myhomelibcorp.infrastructure.content.Fb2ContentExtractor;
import com.myhomelibcorp.infrastructure.content.TxtContentExtractor;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.OptionalLong;

/** One extraction per fresh JVM; use the companion Python runner for A/B RSS measurements. */
public final class ContentExtractionMemoryProbe {
    private ContentExtractionMemoryProbe() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one TXT or FB2 file");
        Path path = Path.of(args[0]).toAbsolutePath();
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".fb2") && !filename.endsWith(".txt")) {
            throw new IllegalArgumentException("Only FB2 and TXT are supported by this probe");
        }
        String format = filename.endsWith(".fb2") ? "fb2" : "txt";
        ContentExtractor extractor = format.equals("fb2") ? new Fb2ContentExtractor() : new TxtContentExtractor();
        byte[] warmup = (format.equals("fb2")
                ? "<FictionBook><body><section><p>Прогрів</p></section></body></FictionBook>" : "Прогрів")
                .getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            extractor.extract(new ContentExtractionRequest(new ContentExtractionSource() {
                public String id() { return "warmup"; }
                public String name() { return "warmup." + format; }
                public InputStream openStream() { return new ByteArrayInputStream(warmup); }
                public OptionalLong size() { return OptionalLong.of(warmup.length); }
            }, format), ContentExtractionContext.none());
        }
        ContentExtractionSource source = new ContentExtractionSource() {
            public String id() { return "measured"; }
            public String name() { return filename; }
            public InputStream openStream() throws IOException { return Files.newInputStream(path); }
            public OptionalLong size() {
                try { return OptionalLong.of(Files.size(path)); }
                catch (IOException e) { return OptionalLong.empty(); }
            }
        };
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long allocatedBefore = bean.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        ExtractedContent content = extractor.extract(new ContentExtractionRequest(source, format), ContentExtractionContext.none());
        long micros = (System.nanoTime() - started) / 1_000;
        long allocated = bean.getThreadAllocatedBytes(thread) - allocatedBefore;
        long anchors = content.chapters().stream().mapToLong(c -> c.anchors().size()).sum();
        // Hash after timing in bounded chunks, including all semantic text and anchor fields.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Writer out = new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(), digest), StandardCharsets.UTF_8)) {
            field(out, content.text());
            for (ExtractedChapter chapter : content.chapters()) {
                field(out, chapter.id()); field(out, chapter.title());
                field(out, Long.toString(chapter.startOffset())); field(out, Long.toString(chapter.endOffset()));
                field(out, chapter.text());
                for (ContentAnchor anchor : chapter.anchors()) {
                    field(out, anchor.id()); field(out, anchor.chapterId()); field(out, anchor.paragraphId());
                    field(out, Long.toString(anchor.startOffset())); field(out, Long.toString(anchor.endOffset()));
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "{\"text_chars\":%d,\"chapters\":%d,\"anchors\":%d,\"allocated_bytes\":%d,\"extraction_us\":%d,\"sha256\":\"%s\"}%n",
                content.textLength(), content.chapters().size(), anchors, allocated, micros,
                HexFormat.of().formatHex(digest.digest()));
    }
    private static void field(Writer out, String text) throws IOException {
        out.write(Integer.toString(text.length())); out.write(':');
        for (int offset = 0; offset < text.length(); offset += 4096) {
            out.write(text, offset, Math.min(4096, text.length() - offset));
        }
    }
}
