package com.myhomelibcorp.reader.core.position;

import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;

import java.util.Optional;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReaderPositionManager {

    private static final int DEFAULT_MAX_CACHED_POSITIONS = 512;
    // Access-order LRU prevents one long-lived reader session from retaining an entry for every
    // book ever opened. The optional provider remains authoritative for evicted positions.
    private final Map<String, ReaderPosition> positions;
    private final PositionProvider provider;

    public interface PositionProvider {
        Optional<ReaderPosition> load(String documentId);
        void save(String documentId, ReaderPosition position);
    }

    public ReaderPositionManager() {
        this(null, DEFAULT_MAX_CACHED_POSITIONS);
    }

    public ReaderPositionManager(PositionProvider provider) {
        this(provider, DEFAULT_MAX_CACHED_POSITIONS);
    }

    ReaderPositionManager(PositionProvider provider, int maxCachedPositions) {
        if (maxCachedPositions < 1) {
            throw new IllegalArgumentException("maxCachedPositions must be positive");
        }
        this.provider = provider;
        this.positions = Collections.synchronizedMap(new LinkedHashMap<String, ReaderPosition>(
                Math.min(128, maxCachedPositions), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ReaderPosition> eldest) {
                return size() > maxCachedPositions;
            }
        });
    }

    public Optional<ReaderPosition> loadPosition(String documentId) {
        if (documentId == null) {
            return Optional.empty();
        }

        ReaderPosition cached = positions.get(documentId);
        if (cached != null) {
            return Optional.of(cached);
        }

        if (provider != null) {
            Optional<ReaderPosition> loaded = provider.load(documentId);
            loaded.ifPresent(pos -> positions.put(documentId, pos));
            return loaded;
        }

        return Optional.empty();
    }

    public void savePosition(String documentId, ReaderPosition position) {
        if (documentId == null || position == null) {
            return;
        }
        positions.put(documentId, position);
        if (provider != null) {
            provider.save(documentId, position);
        }
    }

    public Optional<ReaderPosition> getCurrentPosition(String documentId) {
        return Optional.ofNullable(positions.get(documentId));
    }

    public void clearPosition(String documentId) {
        if (documentId != null) {
            positions.remove(documentId);
        }
    }

    public void clearAll() {
        positions.clear();
    }

    public ReaderPosition validatePosition(ReaderDocument document, ReaderPosition position) {
        if (document == null || position == null) {
            return ReaderPosition.start();
        }

        long maxOffset = Math.max(0L, document.totalTextLength());
        long offset = position.textOffset();
        if (offset < 0 || maxOffset == 0) {
            return ReaderPosition.start();
        }

        int chapterCount = document.chapters() == null ? 0 : document.chapters().size();
        if (offset >= maxOffset) {
            int lastChapter = chapterCount == 0 ? 0 : chapterCount - 1;
            return new ReaderPosition(lastChapter, maxOffset - 1, 0, 0);
        }

        int chapterIndex = position.chapterIndex();
        if (chapterCount == 0) {
            chapterIndex = 0;
        } else if (chapterIndex < 0 || chapterIndex >= chapterCount) {
            chapterIndex = Math.max(0, Math.min(chapterCount - 1, document.chapterIndexAt(offset)));
        }

        int paragraphIndex = Math.max(0, position.paragraphIndex());
        int charOffset = Math.max(0, position.charOffset());
        if (chapterIndex == position.chapterIndex()
                && paragraphIndex == position.paragraphIndex()
                && charOffset == position.charOffset()) {
            return position;
        }
        return new ReaderPosition(chapterIndex, offset, paragraphIndex, charOffset);
    }

    public ReaderPosition startPosition() {
        return ReaderPosition.start();
    }

    public ReaderPosition endPosition(ReaderDocument document) {
        if (document == null) {
            return ReaderPosition.start();
        }
        long maxOffset = Math.max(0L, document.totalTextLength());
        if (maxOffset == 0) {
            return ReaderPosition.start();
        }
        int chapterCount = document.chapters() == null ? 0 : document.chapters().size();
        int lastChapter = chapterCount == 0 ? 0 : chapterCount - 1;
        return new ReaderPosition(lastChapter, maxOffset - 1, 0, 0);
    }
}