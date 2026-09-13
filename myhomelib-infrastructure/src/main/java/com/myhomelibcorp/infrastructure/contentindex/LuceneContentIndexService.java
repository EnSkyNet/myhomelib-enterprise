package com.myhomelibcorp.infrastructure.contentindex;

import com.myhomelibcorp.application.content.ContentAnchor;
import com.myhomelibcorp.application.content.ExtractedChapter;
import com.myhomelibcorp.application.content.index.ContentIndexEntry;
import com.myhomelibcorp.application.content.index.ContentIndexHealth;
import com.myhomelibcorp.application.content.index.ContentIndexHit;
import com.myhomelibcorp.application.content.index.ContentIndexPage;
import com.myhomelibcorp.application.content.index.ContentIndexQuery;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.shared.util.AppPaths;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Independent Lucene full-text index. It never opens or mutates the catalogue metadata index,
 * so content rebuilds have a separate lifecycle and lock domain.
 */
@Component
public final class LuceneContentIndexService implements ContentIndexPort {
    public static final int SCHEMA_VERSION = 1;
    static final String SCHEMA_KEY = "myhomelib.content.schema";
    private static final String FIELD_BOOK_ID = "book_id";
    private static final String FIELD_ARTIFACT_ID = "artifact_id";
    private static final String FIELD_CHAPTER_ID = "chapter_id";
    private static final String FIELD_CHAPTER_TITLE = "chapter_title";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_CHAPTER_START = "chapter_start";
    private static final String FIELD_CHAPTER_END = "chapter_end";
    private static final String FIELD_ANCHOR = "anchor";
    private static final FieldType CONTENT_TEXT_TYPE = contentTextType();
    private static final Analyzer ANALYZER = new StandardAnalyzer();

    private final Function<String, Path> pathResolver;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public LuceneContentIndexService() {
        this(AppPaths::collectionContentIndexDir);
    }

    LuceneContentIndexService(Function<String, Path> pathResolver) {
        this.pathResolver = Objects.requireNonNull(pathResolver, "pathResolver");
    }

    @Override
    public void replaceArtifact(String collectionId, ContentIndexEntry entry) {
        Objects.requireNonNull(entry, "entry");
        ReentrantReadWriteLock.WriteLock lock = lock(collectionId).writeLock();
        lock.lock();
        try {
            Path path = indexPath(collectionId);
            Files.createDirectories(path);
            try (Directory directory = FSDirectory.open(path)) {
                ensureCompatible(directory);
                try (IndexWriter writer = writer(directory, IndexWriterConfig.OpenMode.CREATE_OR_APPEND)) {
                    writer.deleteDocuments(new Term(FIELD_ARTIFACT_ID, entry.artifactId()));
                    addEntry(writer, entry);
                    commit(writer);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot update content index for " + collectionId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteArtifact(String collectionId, String artifactId) {
        deleteByTerm(collectionId, new Term(FIELD_ARTIFACT_ID, required(artifactId, "artifactId")));
    }

    @Override
    public void deleteBook(String collectionId, String bookId) {
        deleteByTerm(collectionId, new Term(FIELD_BOOK_ID, required(bookId, "bookId")));
    }

    @Override
    public void rebuild(String collectionId, Iterable<ContentIndexEntry> entries) {
        String id = required(collectionId, "collectionId");
        Iterable<ContentIndexEntry> source = entries == null ? List.of() : entries;
        ReentrantReadWriteLock.WriteLock lock = lock(id).writeLock();
        lock.lock();
        Path active = indexPath(id);
        Path parent = active.getParent();
        Path rebuild = parent.resolve(active.getFileName() + ".rebuild-" + UUID.randomUUID());
        Path backup = parent.resolve(active.getFileName() + ".backup-" + UUID.randomUUID());
        try {
            Files.createDirectories(parent);
            Files.createDirectories(rebuild);
            try (Directory directory = FSDirectory.open(rebuild);
                 IndexWriter writer = writer(directory, IndexWriterConfig.OpenMode.CREATE)) {
                for (ContentIndexEntry entry : source) {
                    if (entry != null) addEntry(writer, entry);
                }
                commit(writer);
            }
            swapDirectories(active, rebuild, backup);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot rebuild content index for " + id, e);
        } finally {
            deleteTreeQuietly(rebuild);
            deleteTreeQuietly(backup);
            lock.unlock();
        }
    }

    @Override
    public ContentIndexPage search(ContentIndexQuery query) {
        Objects.requireNonNull(query, "query");
        ReentrantReadWriteLock.ReadLock lock = lock(query.collectionId()).readLock();
        lock.lock();
        try {
            Path path = indexPath(query.collectionId());
            if (!Files.isDirectory(path)) return new ContentIndexPage(List.of(), 0, query.offset(), query.limit());
            try (Directory directory = FSDirectory.open(path)) {
                ensureCompatible(directory);
                if (!DirectoryReader.indexExists(directory)) {
                    return new ContentIndexPage(List.of(), 0, query.offset(), query.limit());
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    IndexSearcher searcher = new IndexSearcher(reader);
                    Query textQuery = new QueryParser(FIELD_TEXT, ANALYZER).parse(QueryParser.escape(query.text()));
                    BooleanQuery.Builder builder = new BooleanQuery.Builder().add(textQuery, BooleanClause.Occur.MUST);
                    if (query.bookId() != null) {
                        builder.add(new org.apache.lucene.search.TermQuery(new Term(FIELD_BOOK_ID, query.bookId())), BooleanClause.Occur.FILTER);
                    }
                    Query finalQuery = builder.build();
                    checkInterrupted();
                    int required = Math.min(10_000, query.offset() + query.limit());
                    long total = searcher.count(finalQuery);
                    checkInterrupted();
                    TopDocs top = searcher.search(finalQuery, Math.max(1, required));
                    ScoreDoc[] scoreDocs = top.scoreDocs;
                    int from = Math.min(query.offset(), scoreDocs.length);
                    int to = Math.min(scoreDocs.length, from + query.limit());
                    List<ContentIndexHit> hits = new ArrayList<>(Math.max(0, to - from));
                    for (int i = from; i < to; i++) {
                        checkInterrupted();
                        hits.add(toHit(searcher.storedFields().document(scoreDocs[i].doc), scoreDocs[i].score, query.text()));
                    }
                    return new ContentIndexPage(hits, total, query.offset(), query.limit());
                } catch (org.apache.lucene.queryparser.classic.ParseException e) {
                    throw new IllegalArgumentException("Invalid content search query", e);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot search content index for " + query.collectionId(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ContentIndexHealth health(String collectionId) {
        String id = required(collectionId, "collectionId");
        ReentrantReadWriteLock.ReadLock lock = lock(id).readLock();
        lock.lock();
        try {
            Path path = indexPath(id);
            if (!Files.isDirectory(path)) return new ContentIndexHealth(id, SCHEMA_VERSION, 0, 0, true, "index absent");
            try (Directory directory = FSDirectory.open(path)) {
                if (!DirectoryReader.indexExists(directory)) {
                    return new ContentIndexHealth(id, SCHEMA_VERSION, 0, directorySize(path), true, "index empty");
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    int actual = schemaVersion(reader.getIndexCommit());
                    boolean compatible = actual == SCHEMA_VERSION;
                    return new ContentIndexHealth(id, actual, reader.numDocs(), directorySize(path), compatible,
                            compatible ? "ok" : "schema mismatch; rebuild required");
                }
            }
        } catch (IOException e) {
            return new ContentIndexHealth(id, -1, 0, 0, false, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private void deleteByTerm(String collectionId, Term term) {
        String id = required(collectionId, "collectionId");
        ReentrantReadWriteLock.WriteLock lock = lock(id).writeLock();
        lock.lock();
        try {
            Path path = indexPath(id);
            if (!Files.isDirectory(path)) return;
            try (Directory directory = FSDirectory.open(path)) {
                ensureCompatible(directory);
                if (!DirectoryReader.indexExists(directory)) return;
                try (IndexWriter writer = writer(directory, IndexWriterConfig.OpenMode.CREATE_OR_APPEND)) {
                    writer.deleteDocuments(term);
                    commit(writer);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete from content index for " + id, e);
        } finally {
            lock.unlock();
        }
    }

    private static void addEntry(IndexWriter writer, ContentIndexEntry entry) throws IOException {
        for (ExtractedChapter chapter : entry.content().chapters()) {
            Document document = new Document();
            document.add(new StringField(FIELD_BOOK_ID, entry.bookId(), Field.Store.YES));
            document.add(new StringField(FIELD_ARTIFACT_ID, entry.artifactId(), Field.Store.YES));
            document.add(new StringField(FIELD_CHAPTER_ID, chapter.id(), Field.Store.YES));
            document.add(new Field(FIELD_CHAPTER_TITLE, chapter.title(), CONTENT_TEXT_TYPE));
            document.add(new Field(FIELD_TEXT, chapter.text(), CONTENT_TEXT_TYPE));
            document.add(new LongPoint(FIELD_CHAPTER_START, chapter.startOffset()));
            document.add(new StoredField(FIELD_CHAPTER_START, chapter.startOffset()));
            document.add(new LongPoint(FIELD_CHAPTER_END, chapter.endOffset()));
            document.add(new StoredField(FIELD_CHAPTER_END, chapter.endOffset()));
            for (ContentAnchor anchor : chapter.anchors()) {
                document.add(new StoredField(FIELD_ANCHOR, encodeAnchor(anchor)));
            }
            writer.addDocument(document);
        }
    }

    private static ContentIndexHit toHit(Document document, float score, String queryText) {
        long chapterStart = numeric(document, FIELD_CHAPTER_START);
        long chapterEnd = numeric(document, FIELD_CHAPTER_END);
        String text = document.get(FIELD_TEXT);
        MatchPreview preview = preview(text, queryText, chapterStart);
        return new ContentIndexHit(
                document.get(FIELD_BOOK_ID), document.get(FIELD_ARTIFACT_ID), document.get(FIELD_CHAPTER_ID),
                document.get(FIELD_CHAPTER_TITLE), chapterStart, chapterEnd, preview.matchOffset(), preview.snippet(), score);
    }

    private static MatchPreview preview(String text, String queryText, long chapterStart) {
        String source = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (source.isEmpty()) return new MatchPreview(chapterStart, "");
        String lower = source.toLowerCase(Locale.ROOT);
        int match = -1;
        if (queryText != null) {
            for (String token : queryText.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() < 2) continue;
                int candidate = lower.indexOf(token);
                if (candidate >= 0 && (match < 0 || candidate < match)) match = candidate;
            }
        }
        if (match < 0) match = 0;
        int start = Math.max(0, match - 70);
        int end = Math.min(source.length(), Math.max(match + 150, start + 180));
        String snippet = (start > 0 ? "…" : "") + source.substring(start, end).trim() + (end < source.length() ? "…" : "");
        return new MatchPreview(chapterStart + match, snippet);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("content search cancelled");
    }

    private record MatchPreview(long matchOffset, String snippet) { }

    private static long numeric(Document document, String field) {
        for (var value : document.getFields(field)) {
            if (value.numericValue() != null) return value.numericValue().longValue();
        }
        return 0L;
    }

    private static String encodeAnchor(ContentAnchor anchor) {
        return anchor.id().replace("|", "_") + "|" + anchor.startOffset() + "|" + anchor.endOffset();
    }

    private static IndexWriter writer(Directory directory, IndexWriterConfig.OpenMode mode) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(ANALYZER);
        config.setOpenMode(mode);
        config.setCommitOnClose(false);
        return new IndexWriter(directory, config);
    }

    private static void commit(IndexWriter writer) throws IOException {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(SCHEMA_KEY, Integer.toString(SCHEMA_VERSION));
        writer.setLiveCommitData(metadata.entrySet());
        writer.commit();
    }

    private static void ensureCompatible(Directory directory) throws IOException {
        if (!DirectoryReader.indexExists(directory)) return;
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            int actual = schemaVersion(reader.getIndexCommit());
            if (actual != SCHEMA_VERSION) {
                throw new IllegalStateException("Content index schema " + actual + " is incompatible with " + SCHEMA_VERSION + "; rebuild required");
            }
        }
    }

    private static int schemaVersion(IndexCommit commit) throws IOException {
        try {
            return Integer.parseInt(commit.getUserData().getOrDefault(SCHEMA_KEY, "-1"));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static FieldType contentTextType() {
        FieldType type = new FieldType();
        type.setStored(true);
        type.setTokenized(true);
        type.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        type.setStoreTermVectors(false);
        type.freeze();
        return type;
    }

    private ReentrantReadWriteLock lock(String collectionId) {
        String id = required(collectionId, "collectionId");
        return locks.computeIfAbsent(id, ignored -> new ReentrantReadWriteLock());
    }

    private Path indexPath(String collectionId) {
        return pathResolver.apply(required(collectionId, "collectionId")).toAbsolutePath().normalize();
    }

    private static void swapDirectories(Path active, Path rebuild, Path backup) throws IOException {
        boolean hadActive = Files.exists(active);
        if (hadActive) move(active, backup);
        try {
            move(rebuild, active);
            deleteTreeQuietly(backup);
        } catch (IOException failure) {
            if (hadActive && Files.exists(backup) && !Files.exists(active)) {
                try { move(backup, active); } catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static long directorySize(Path path) throws IOException {
        if (!Files.exists(path)) return 0L;
        try (var stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).mapToLong(item -> {
                try { return Files.size(item); } catch (IOException ignored) { return 0L; }
            }).sum();
        }
    }

    private static void deleteTreeQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(item);
        } catch (IOException ignored) { }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
