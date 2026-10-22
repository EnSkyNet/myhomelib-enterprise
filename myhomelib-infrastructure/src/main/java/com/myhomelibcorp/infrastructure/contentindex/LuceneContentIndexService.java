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
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.application.util.SafeFilePublication;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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
    // Extraction semantics are part of the schema: EPUB block separators and TXT UTF-8
    // boundary decoding changed, so v1 offsets must not be paired with newly extracted text.
    public static final int SCHEMA_VERSION = 2;
    static final String SCHEMA_KEY = "myhomelib.content.schema";
    private static final String FIELD_BOOK_ID = "book_id";
    private static final String FIELD_ARTIFACT_ID = "artifact_id";
    private static final String FIELD_CHAPTER_ID = "chapter_id";
    private static final String FIELD_CHAPTER_TITLE = "chapter_title";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_CHAPTER_START = "chapter_start";
    private static final String FIELD_CHAPTER_END = "chapter_end";
    private static final String FIELD_ANCHOR = "anchor";
    private static final Set<String> HIT_STORED_FIELDS = Set.of(
            FIELD_BOOK_ID, FIELD_ARTIFACT_ID, FIELD_CHAPTER_ID, FIELD_CHAPTER_TITLE, FIELD_TEXT,
            FIELD_CHAPTER_START, FIELD_CHAPTER_END);
    private static final FieldType CONTENT_TEXT_TYPE = contentTextType();
    private static final Analyzer ANALYZER = new StandardAnalyzer();
    private static final Pattern PREVIEW_TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern PREVIEW_WHITESPACE = Pattern.compile("\\s+");

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
            Path path = ensureSafeIndexDirectory(indexPath(collectionId), true);
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
            SafeFilePublication.createDirectoriesNoLinks(parent, "Content index parent");
            SafeFilePublication.createDirectoriesNoLinks(rebuild, "Content index rebuild directory");
            if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.requireSafeDirectoryPath(active, "Content index active directory");
            }
            try (Directory directory = FSDirectory.open(rebuild);
                 IndexWriter writer = writer(directory, IndexWriterConfig.OpenMode.CREATE)) {
                for (ContentIndexEntry entry : source) {
                    if (entry != null) addEntry(writer, entry);
                }
                commit(writer);
            }
            swapDirectories(active, rebuild, backup);
            // Freshness is an application-level queue/orchestration decision. A committed download
            // may arrive while this rebuild is publishing. Clearing STALE here would create a
            // false-fresh window before the queued incremental repair is visible. The maintenance
            // service acknowledges the rebuild and releases its own stale hold after publication.
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
            if (isMarkedStale(query.collectionId())) {
                throw new IllegalStateException("Content index is stale and must be rebuilt");
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new ContentIndexPage(List.of(), 0, query.offset(), query.limit());
            ensureSafeIndexDirectory(path, false);
            try (Directory directory = FSDirectory.open(path)) {
                if (!DirectoryReader.indexExists(directory)) {
                    return new ContentIndexPage(List.of(), 0, query.offset(), query.limit());
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    ensureCompatible(reader);
                    IndexSearcher searcher = new IndexSearcher(reader);
                    Query textQuery = new QueryParser(FIELD_TEXT, ANALYZER).parse(QueryParser.escape(query.text()));
                    BooleanQuery.Builder builder = new BooleanQuery.Builder().add(textQuery, BooleanClause.Occur.MUST);
                    if (query.bookId() != null) {
                        builder.add(new org.apache.lucene.search.TermQuery(new Term(FIELD_BOOK_ID, query.bookId())), BooleanClause.Occur.FILTER);
                    }
                    Query finalQuery = builder.build();
                    List<Pattern> previewPatterns = previewPatterns(query.text());
                    checkInterrupted();
                    int required = (int) Math.min(10_000L, (long) query.offset() + query.limit());
                    TopDocs top = searcher.search(finalQuery, Math.max(1, required));
                    long total = top.totalHits.value();
                    if (top.totalHits.relation() != org.apache.lucene.search.TotalHits.Relation.EQUAL_TO) {
                        checkInterrupted();
                        total = searcher.count(finalQuery);
                    }
                    checkInterrupted();
                    ScoreDoc[] scoreDocs = top.scoreDocs;
                    int from = Math.min(query.offset(), scoreDocs.length);
                    int to = Math.min(scoreDocs.length, from + query.limit());
                    List<ContentIndexHit> hits = new ArrayList<>(Math.max(0, to - from));
                    for (int i = from; i < to; i++) {
                        checkInterrupted();
                        hits.add(toHit(searcher.storedFields().document(scoreDocs[i].doc, HIT_STORED_FIELDS), scoreDocs[i].score, previewPatterns));
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
            boolean stale = isMarkedStale(id);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new ContentIndexHealth(id, SCHEMA_VERSION, 0, 0, true,
                        stale ? ContentIndexHealth.State.STALE : ContentIndexHealth.State.ABSENT, "");
            }
            ensureSafeIndexDirectory(path, false);
            try (Directory directory = FSDirectory.open(path)) {
                if (!DirectoryReader.indexExists(directory)) {
                    return new ContentIndexHealth(id, SCHEMA_VERSION, 0, directorySize(path), true,
                            stale ? ContentIndexHealth.State.STALE : ContentIndexHealth.State.EMPTY, "");
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    int actual = schemaVersion(reader.getIndexCommit());
                    boolean compatible = actual == SCHEMA_VERSION;
                    ContentIndexHealth.State state = !compatible
                            ? ContentIndexHealth.State.SCHEMA_MISMATCH
                            : (stale ? ContentIndexHealth.State.STALE : ContentIndexHealth.State.READY);
                    return new ContentIndexHealth(id, actual, reader.numDocs(), directorySize(path), compatible, state, "");
                }
            }
        } catch (IOException e) {
            return new ContentIndexHealth(id, -1, 0, 0, false, ContentIndexHealth.State.ERROR, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markStale(String collectionId) {
        String id = required(collectionId, "collectionId");
        try {
            AtomicFileSupport.writeStringReplacing(AppPaths.collectionContentIndexStateFile(id),
                    "STALE\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot mark content index stale for " + id, e);
        }
    }

    @Override
    public void markFresh(String collectionId) {
        String id = required(collectionId, "collectionId");
        Path marker = AppPaths.collectionContentIndexStateFile(id);
        try {
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
            SafeFilePublication.requireSafeRegularFile(marker, "Content index stale marker");
            Files.delete(marker);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot clear content index stale marker for " + id, e);
        }
    }

    private static boolean isMarkedStale(String collectionId) {
        Path marker = AppPaths.collectionContentIndexStateFile(collectionId);
        return Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
    }

    private void deleteByTerm(String collectionId, Term term) {
        String id = required(collectionId, "collectionId");
        ReentrantReadWriteLock.WriteLock lock = lock(id).writeLock();
        lock.lock();
        try {
            Path path = indexPath(id);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            ensureSafeIndexDirectory(path, false);
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

    private static ContentIndexHit toHit(Document document, float score, List<Pattern> previewPatterns) {
        long chapterStart = numeric(document, FIELD_CHAPTER_START);
        long chapterEnd = numeric(document, FIELD_CHAPTER_END);
        String text = document.get(FIELD_TEXT);
        MatchPreview preview = preview(text, previewPatterns, chapterStart);
        return new ContentIndexHit(
                document.get(FIELD_BOOK_ID), document.get(FIELD_ARTIFACT_ID), document.get(FIELD_CHAPTER_ID),
                document.get(FIELD_CHAPTER_TITLE), chapterStart, chapterEnd, preview.matchOffset(), preview.snippet(), score);
    }

    private static List<Pattern> previewPatterns(String queryText) {
        if (queryText == null || queryText.isBlank()) return List.of();
        List<Pattern> patterns = new ArrayList<>();
        for (String token : PREVIEW_TOKEN_SPLIT.split(queryText)) {
            if (token.length() < 2) continue;
            patterns.add(Pattern.compile(Pattern.quote(token), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(patterns);
    }

    private static MatchPreview preview(String text, List<Pattern> patterns, long chapterStart) {
        String source = text == null ? "" : text;
        if (source.isEmpty()) return new MatchPreview(chapterStart, "");
        int match = -1;
        for (Pattern pattern : patterns) {
            var matcher = pattern.matcher(source);
            if (matcher.find()) {
                int candidate = matcher.start();
                if (match < 0 || candidate < match) match = candidate;
            }
        }
        if (match < 0) match = 0;
        int start = Math.max(0, match - 70);
        int end = Math.min(source.length(), Math.max(match + 150, start + 180));
        String body = PREVIEW_WHITESPACE.matcher(source.substring(start, end)).replaceAll(" ").trim();
        String snippet = (start > 0 ? "…" : "") + body + (end < source.length() ? "…" : "");
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
            ensureCompatible(reader);
        }
    }

    private static void ensureCompatible(DirectoryReader reader) throws IOException {
        int actual = schemaVersion(reader.getIndexCommit());
        if (actual != SCHEMA_VERSION) {
            throw new IllegalStateException("Content index schema " + actual + " is incompatible with " + SCHEMA_VERSION + "; rebuild required");
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
        boolean hadActive = Files.exists(active, LinkOption.NOFOLLOW_LINKS);
        if (hadActive) moveDirectoryNoReplace(active, backup, "Content index backup");
        try {
            moveDirectoryNoReplace(rebuild, active, "Content index activation");
            deleteTreeQuietly(backup);
        } catch (IOException failure) {
            if (hadActive && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
                try { moveDirectoryNoReplace(backup, active, "Content index rollback"); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    private static Path ensureSafeIndexDirectory(Path path, boolean create) throws IOException {
        if (create) return SafeFilePublication.createDirectoriesNoLinks(path, "Content index directory");
        return SafeFilePublication.requireSafeDirectoryPath(path, "Content index directory");
    }

    private static void moveDirectoryNoReplace(Path source, Path target, String role) throws IOException {
        Path safeSource = SafeFilePublication.requireSafeDirectoryPath(source, role + " source");
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException(role + " target has no parent: " + target);
        SafeFilePublication.createDirectoriesNoLinks(parent, role + " parent");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        try {
            Files.move(safeSource, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(safeSource, target);
        }
        SafeFilePublication.requireSafeDirectoryPath(target, role + " target");
    }

    private static long directorySize(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0L;
        SafeFilePublication.requireSafeDirectoryPath(path, "Content index size directory");
        long total = 0L;
        // Lucene stores index files in the collection directory itself. A non-recursive listing
        // avoids walking the tree for every health check that precedes a content-search request.
        try (var files = Files.list(path)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                long size;
                try { size = Files.size(file); }
                catch (IOException ignored) { continue; }
                if (size > Long.MAX_VALUE - total) return Long.MAX_VALUE;
                total += size;
            }
        }
        return total;
    }

    // Package-private for filesystem regressions: cleanup must not retain every index path.
    static void deleteTreeQuietly(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Path safeRoot = SafeFilePublication.requireSafeDirectoryPath(
                    path, "Content index cleanup directory");
            // Preserve ALL index evidence when an unexpected link or special file is present:
            // preflight without mutating anything, and without collecting paths in memory.
            Files.walkFileTree(safeRoot, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                        throw new IOException("Unsafe content index cleanup entry: " + file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            // A second streaming walk deletes only validated regular files, then directories.
            // Recheck each entry in case it has been replaced after preflight.
            Files.walkFileTree(safeRoot, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                        throw new IOException("Unsafe content index cleanup entry: " + file);
                    }
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                        throws IOException {
                    if (failure != null) throw failure;
                    if (Files.isSymbolicLink(directory)) {
                        throw new IOException("Unsafe content index cleanup directory: " + directory);
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { /* preserve unexpected replacement as evidence */ }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
