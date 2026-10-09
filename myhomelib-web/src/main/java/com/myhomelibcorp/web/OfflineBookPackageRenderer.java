package com.myhomelibcorp.web;

import com.myhomelibcorp.application.webreader.WebReaderChapter;
import com.myhomelibcorp.application.webreader.WebReaderDocument;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Manifest for an explicit offline-book projection. The Service Worker caches only
 * server-rendered, sanitized Web Reader pages listed here; original book binaries are never cached.
 */
public final class OfflineBookPackageRenderer {
    public static final int SCHEMA_VERSION = 1;

    public String manifest(WebReaderDocument document) {
        Objects.requireNonNull(document, "document");
        if (!document.supported() || document.chapters().isEmpty()) {
            throw new IllegalArgumentException("Offline package requires a supported Web Reader document");
        }
        String id = encode(document.bookId());
        StringBuilder out = new StringBuilder(512 + document.chapters().size() * 48);
        out.append('{')
                .append("\"schemaVersion\":").append(SCHEMA_VERSION).append(',')
                .append("\"bookId\":\"").append(json(document.bookId())).append("\",")
                .append("\"revision\":\"").append(revision(document)).append("\",")
                .append("\"detailUrl\":\"/web/books/").append(id).append("\",")
                .append("\"chapterUrls\":[");
        for (int i = 0; i < document.chapters().size(); i++) {
            if (i > 0) out.append(',');
            out.append("\"/web/read/").append(id).append("?chapter=").append(i).append("\"");
        }
        return out.append("]}").toString();
    }

    public String revision(WebReaderDocument document) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, document.bookId());
            update(digest, document.format());
            update(digest, document.title());
            for (WebReaderChapter chapter : document.chapters()) {
                update(digest, Integer.toString(chapter.index()));
                update(digest, chapter.id());
                update(digest, chapter.title());
                update(digest, Long.toString(chapter.startOffset()));
                update(digest, Long.toString(chapter.endOffset()));
                update(digest, chapter.text());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String json(String value) {
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
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
