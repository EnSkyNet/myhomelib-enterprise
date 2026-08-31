package com.myhomelibcorp.reader.format.zip;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.resource.HybridResourceRepository;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** Builds one Reader document from several books stored in the same archive. */
final class ZipDocumentMerger {
    private ZipDocumentMerger() { }

    static ReaderDocument merge(BookSource archive, List<ReaderDocument> documents) throws IOException {
        if (documents == null || documents.isEmpty()) return null;
        if (documents.size() == 1) return documents.getFirst();

        TextStorageImpl text = new TextStorageImpl();
        HybridResourceRepository resources = new HybridResourceRepository();
        DefaultTableOfContents toc = new DefaultTableOfContents();
        List<ChapterIndex> chapters = new ArrayList<>();
        LinkedHashSet<String> authors = new LinkedHashSet<>();
        LinkedHashSet<String> genres = new LinkedHashSet<>();
        LinkedHashSet<String> languages = new LinkedHashSet<>();

        try {
            for (int index = 0; index < documents.size(); index++) {
                ReaderDocument document = documents.get(index);
                if (document == null || document.text() == null || document.text().length() == 0) continue;

                int bookNumber = index + 1;
                long baseOffset = text.length();
                Map<String, String> resourceIds = copyResources(document.resources(), resources, bookNumber);
                appendText(document.text(), text, resourceIds);

                BookMetadata metadata = document.metadata();
                String title = metadata != null && metadata.title() != null && !metadata.title().isBlank()
                        ? metadata.title().trim()
                        : "Книга " + bookNumber;
                toc.addEntry(new TocEntry(title, baseOffset, 0,
                        shiftedToc(document.toc() == null ? List.of() : document.toc().entries(), baseOffset, 1)));

                if (document.chapters() != null) {
                    int chapterNo = 0;
                    for (ChapterIndex chapter : document.chapters()) {
                        if (chapter == null) continue;
                        chapters.add(new ChapterIndex(
                                "zip" + bookNumber + "-" + (++chapterNo) + "-" + safeId(chapter.id()),
                                chapter.title(),
                                baseOffset + chapter.startOffset(),
                                baseOffset + chapter.endOffset(),
                                chapter.paragraphCount()));
                    }
                }

                if (metadata != null) {
                    if (metadata.authors() != null) metadata.authors().stream().filter(ZipDocumentMerger::nonBlank).forEach(authors::add);
                    if (metadata.genres() != null) metadata.genres().stream().filter(ZipDocumentMerger::nonBlank).forEach(genres::add);
                    if (nonBlank(metadata.language())) languages.add(metadata.language());
                }
            }

            if (text.length() == 0) {
                resources.close();
                return null;
            }

            String archiveTitle = archiveTitle(archive == null ? null : archive.name());
            String language = languages.size() == 1 ? languages.iterator().next() : "und";
            long sourceSize = archive != null && archive.size().isPresent() ? archive.size().getAsLong() : 0L;
            BookMetadata metadata = new BookMetadata(
                    archive == null ? "archive" : archive.id(),
                    archiveTitle,
                    authors.isEmpty() ? List.of("Невідомий автор") : List.copyOf(authors),
                    language,
                    null,
                    null,
                    List.copyOf(genres),
                    "Архів містить " + documents.size() + " книг.",
                    "",
                    "",
                    null,
                    sourceSize);

            if (chapters.isEmpty()) {
                chapters.add(new ChapterIndex("zip-1", archiveTitle, 0, text.length(), text.getParagraphCount()));
            }

            return CompactReaderDocument.builder()
                    .metadata(metadata)
                    .chapters(List.copyOf(chapters))
                    .resources(resources)
                    .text(text)
                    .toc(toc)
                    .totalTextLength(text.length())
                    .build();
        } catch (Throwable error) {
            resources.close();
            if (error instanceof IOException io) throw io;
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Не вдалося об'єднати книги з архіву", error);
        }
    }

    private static Map<String, String> copyResources(ResourceRepository source,
                                                      HybridResourceRepository target,
                                                      int bookNumber) throws IOException {
        if (source == null) return Map.of();
        Map<String, String> ids = new LinkedHashMap<>();
        for (String id : source.getAllIds()) {
            if (!nonBlank(id)) continue;
            String namespaced = "book" + bookNumber + "-" + id;
            ids.put(id, namespaced);
            ResourceInfo info = source.getInfo(id).orElse(null);
            String mime = info == null ? "application/octet-stream" : info.mimeType();
            Optional<InputStream> opened = source.open(id);
            if (opened.isPresent()) {
                try (InputStream in = opened.get()) {
                    if (!target.add(namespaced, mime, in, ArchiveSafetyLimits.MAX_ENTRY_BYTES)) {
                        throw new IOException("Ресурс архівної книги перевищує безпечний ліміт: " + id);
                    }
                }
            } else {
                target.addMetadata(namespaced, mime);
            }
        }
        return Map.copyOf(ids);
    }

    private static void appendText(TextStorage source, TextStorageImpl target, Map<String, String> resourceIds) {
        List<ParagraphInfo> paragraphs = source.getParagraphs();
        if (paragraphs == null || paragraphs.isEmpty()) {
            appendParagraph(source, target, 0, source.length(), TextStyle.NORMAL, resourceIds);
            return;
        }
        for (int i = 0; i < paragraphs.size(); i++) {
            ParagraphInfo paragraph = paragraphs.get(i);
            int start = Math.max(0, Math.min(source.length(), paragraph.offset()));
            int end = i + 1 < paragraphs.size()
                    ? Math.max(start, Math.min(source.length(), paragraphs.get(i + 1).offset()))
                    : source.length();
            appendParagraph(source, target, start, end,
                    paragraph.style() == null ? TextStyle.NORMAL : paragraph.style(), resourceIds);
        }
    }

    private static void appendParagraph(TextStorage source, TextStorageImpl target, int start, int end,
                                        TextStyle paragraphStyle, Map<String, String> resourceIds) {
        String raw = source.getText(start, end);
        Transformed transformed = namespaceImageMarkers(raw, resourceIds);
        int base = target.startParagraph(paragraphStyle);
        target.append(transformed.text(), TextStyle.NORMAL);
        for (StyleSpan span : source.getSpans(start, end)) {
            int mappedStart = transformed.map(span.start());
            int mappedEnd = transformed.map(span.end());
            if (mappedEnd > mappedStart) target.addSpan(base + mappedStart, base + mappedEnd, span.style());
        }
    }

    private static Transformed namespaceImageMarkers(String raw, Map<String, String> resourceIds) {
        if (raw == null || raw.isEmpty() || resourceIds.isEmpty() || raw.indexOf("[IMAGE:") < 0) {
            return Transformed.identity(raw == null ? "" : raw);
        }
        StringBuilder out = new StringBuilder(raw.length() + 32);
        int[] map = new int[raw.length() + 1];
        int i = 0;
        while (i < raw.length()) {
            map[i] = out.length();
            if (raw.startsWith("[IMAGE:", i)) {
                int close = raw.indexOf(']', i + 7);
                if (close > i) {
                    String oldId = raw.substring(i + 7, close);
                    String newId = resourceIds.get(oldId);
                    if (newId != null) {
                        String replacement = "[IMAGE:" + newId + "]";
                        int oldLen = close + 1 - i;
                        int newLen = replacement.length();
                        int outStart = out.length();
                        for (int k = 0; k <= oldLen; k++) {
                            map[i + k] = outStart + (int) Math.round((double) k * newLen / oldLen);
                        }
                        out.append(replacement);
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(raw.charAt(i));
            i++;
        }
        map[raw.length()] = out.length();
        return new Transformed(out.toString(), map);
    }

    private static List<TocEntry> shiftedToc(List<TocEntry> entries, long offset, int minLevel) {
        if (entries == null || entries.isEmpty()) return List.of();
        List<TocEntry> result = new ArrayList<>(entries.size());
        for (TocEntry entry : entries) {
            if (entry == null) continue;
            int level = Math.max(minLevel, entry.level() + 1);
            result.add(new TocEntry(
                    entry.title(),
                    offset + entry.textOffset(),
                    level,
                    shiftedToc(entry.children(), offset, level + 1)));
        }
        return List.copyOf(result);
    }

    private static String archiveTitle(String name) {
        if (!nonBlank(name)) return "Архів книг";
        String value = name.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        for (String suffix : List.of(".fb2.zip", ".fb2zip", ".zip")) {
            if (lower.endsWith(suffix) && value.length() > suffix.length()) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String safeId(String value) {
        return nonBlank(value) ? value.replaceAll("[^A-Za-z0-9._-]", "_") : "chapter";
    }

    private record Transformed(String text, int[] offsets) {
        static Transformed identity(String text) {
            int[] offsets = new int[text.length() + 1];
            for (int i = 0; i < offsets.length; i++) offsets[i] = i;
            return new Transformed(text, offsets);
        }
        int map(int original) {
            if (original <= 0) return 0;
            if (original >= offsets.length) return text.length();
            return offsets[original];
        }
    }
}
