package com.myhomelibcorp.reader.format.txt;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.resource.HybridResourceRepository;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;

import java.io.*;
import java.util.List;
import java.util.OptionalLong;

/** Streaming parser for TXT/TEXT/Markdown books. */
public final class TxtParser implements BookParser {
    private static final int MAX_CHARS = 300_000_000;

    @Override
    public BookDocumentMetadata readMetadata(BookSource source) throws IOException {
        if (source == null) throw new IOException("TXT source is null");
        String title = fallbackTitle(source.name());
        try (BufferedReader reader = TextEncodingDetector.open(source.openStream(), null)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) { title = line.strip(); break; }
            }
        }
        BookMetadata metadata = metadata(source, title);
        return new BookDocumentMetadataSnapshot(metadata, source.size().orElse(0), false, 1);
    }

    @Override
    public ReaderDocument parse(BookSource source, ParseOptions options) throws IOException {
        if (source == null) throw new IOException("TXT source is null");
        ParseOptions effective = options != null ? options : ParseOptions.defaultOptions();
        TextStorageImpl text = new TextStorageImpl();
        String title = fallbackTitle(source.name());
        boolean titleFound = false;

        try (BufferedReader reader = TextEncodingDetector.open(source.openStream(), effective.preferredEncoding())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("TXT parsing cancelled");
                if (!titleFound && !line.isBlank()) {
                    title = line.strip();
                    titleFound = true;
                }
                if ((long) text.length() + line.length() + 1 > MAX_CHARS)
                    throw new IOException("TXT exceeds Reader safety limit of " + MAX_CHARS + " characters");
                text.startParagraph(TextStyle.NORMAL);
                text.append(line, TextStyle.NORMAL);
                text.append("\n", TextStyle.NORMAL);
            }
        }

        if (text.length() == 0) throw new IOException("TXT file is empty");
        BookMetadata metadata = metadata(source, title);
        ChapterIndex chapter = new ChapterIndex("text", title, 0, text.length(), text.getParagraphCount());
        DefaultTableOfContents toc = new DefaultTableOfContents();
        if (effective.buildToc()) toc.addEntry(title, 0, 1);
        return CompactReaderDocument.builder()
                .metadata(metadata)
                .chapters(List.of(chapter))
                .resources(new HybridResourceRepository())
                .text(text)
                .toc(toc)
                .totalTextLength(text.length())
                .build();
    }

    private BookMetadata metadata(BookSource source, String title) {
        OptionalLong size = source.size();
        return new BookMetadata(source.id(), title, List.of("Невідомий автор"), "", null, null,
                List.of(), "", "", "", null, size.orElse(0));
    }

    private String fallbackTitle(String name) {
        if (name == null || name.isBlank()) return "Без назви";
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).strip();
    }
}
