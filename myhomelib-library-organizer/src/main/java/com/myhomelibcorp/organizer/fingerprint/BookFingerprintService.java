package com.myhomelibcorp.organizer.fingerprint;

import com.myhomelibcorp.epub.optimizer.semantic.EpubSemanticSnapshot;
import com.myhomelibcorp.epub.optimizer.semantic.EpubSemanticSnapshotService;
import com.myhomelibcorp.organizer.detect.DetectedBookFormat;
import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes physical/semantic fingerprints and keeps a bounded stat-keyed cache for repeated Analyze runs.
 *
 * <p>The cache is only a planning/performance aid. Apply never trusts it for destructive proof: sources are
 * SHA-256 verified again against the plan before any filesystem mutation and again before prepared commit.</p>
 */
@Service
public final class BookFingerprintService {
    private static final int MAX_CACHE_ENTRIES = 100_000;
    private final EpubSemanticSnapshotService epubSnapshots;
    private final Map<StatKey, BookFingerprint> cache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<StatKey, BookFingerprint> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    public BookFingerprintService(EpubSemanticSnapshotService epubSnapshots) { this.epubSnapshots = epubSnapshots; }

    public BookFingerprint fingerprint(Path source, DetectedBookFormat format) throws Exception {
        Path file = OrganizerPathSafety.canonicalExistingFile(source, "Organizer fingerprint source");
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IllegalArgumentException("Fingerprint source is not a regular file: " + file);
        StatKey key = new StatKey(file, attributes.size(), attributes.lastModifiedTime().toMillis(),
                attributes.fileKey() == null ? "" : String.valueOf(attributes.fileKey()),
                format == null ? "" : format.format());
        synchronized (cache) {
            BookFingerprint cached = cache.get(key);
            if (cached != null) return cached;
        }

        String physical = sha256(file);
        String semantic = physical;
        if (format != null && "epub".equals(format.format())) {
            EpubSemanticSnapshot snapshot = epubSnapshots.snapshot(file);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String path : snapshot.spineOrder()) {
                update(digest, path);
                update(digest, snapshot.chapterTextHashes().getOrDefault(path, ""));
            }
            snapshot.metadata().forEach((k, v) -> { update(digest, k); update(digest, v); });
            semantic = HexFormat.of().formatHex(digest.digest());
        }
        BookFingerprint computed = new BookFingerprint(physical, semantic, attributes.size(), attributes.lastModifiedTime().toMillis());
        synchronized (cache) { cache.put(key, computed); }
        return computed;
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = in.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        if (value != null) digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private record StatKey(Path path, long size, long modifiedMillis, String fileKey, String format) { }
}
