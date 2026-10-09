package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.kobo.*;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.CollectionTransactionExecutor;
import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreContext;
import com.myhomelibcorp.shared.security.SecretStoreProvider;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** SQLite-backed incremental Kobo state plus OS-secret-store credentials. */
@Repository
public class SqliteKoboSyncAdapter implements KoboSyncPort {
    private static final String PREFIX = "kobo.sync.";
    private static final String DEVICE_ID = PREFIX + "device_id";
    private static final String ENABLED = PREFIX + "enabled";
    private static final String LAST_SYNC = PREFIX + "last_sync";
    private static final String LAST_ERROR = PREFIX + "last_error";
    private static final String SECRET_PREFIX = "myhomelib.kobo.token.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CollectionManager collections;
    private final ApplicationSettingsPort settings;
    private final AtomicReference<Optional<SecretStore>> secretStore = new AtomicReference<>();

    @Autowired
    public SqliteKoboSyncAdapter(CollectionManager collections, ApplicationSettingsPort settings) {
        this(collections, settings, null);
    }

    SqliteKoboSyncAdapter(CollectionManager collections, ApplicationSettingsPort settings, Optional<SecretStore> testSecretStore) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (testSecretStore != null) this.secretStore.set(testSecretStore);
    }

    @Override
    public KoboDeviceStatus status() {
        Optional<SecretStore> secrets = secrets();
        String deviceId = settings.get(DEVICE_ID, "").trim();
        boolean enabled = settings.getBoolean(ENABLED, false) && !deviceId.isBlank()
                && secrets.flatMap(store -> store.read(secretKey(deviceId))).filter(v -> !v.isBlank()).isPresent();
        return new KoboDeviceStatus(deviceId, enabled, secrets.isPresent(),
                secrets.map(SecretStore::backendId).orElse(""), parseInstant(settings.get(LAST_SYNC, "")),
                settings.get(LAST_ERROR, ""));
    }

    @Override
    public synchronized KoboCredentialIssue enableOrRotate() {
        SecretStore store = secrets().orElseThrow(() -> new IllegalStateException("Secure secret store is unavailable"));
        String deviceId = settings.get(DEVICE_ID, "").trim();
        if (deviceId.isBlank()) deviceId = UUID.randomUUID().toString();
        String token = randomToken();
        store.write(secretKey(deviceId), token);
        settings.put(DEVICE_ID, deviceId);
        settings.putBoolean(ENABLED, true);
        settings.remove(LAST_ERROR);
        ensureMeta(deviceId);
        return new KoboCredentialIssue(deviceId, token);
    }

    @Override
    public synchronized void revoke() {
        String deviceId = settings.get(DEVICE_ID, "").trim();
        if (!deviceId.isBlank()) secrets().ifPresent(store -> store.delete(secretKey(deviceId)));
        settings.putBoolean(ENABLED, false);
        settings.remove(LAST_ERROR);
    }

    @Override
    public boolean authenticate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) return false;
        KoboDeviceStatus status = status();
        if (!status.enabled()) return false;
        return secrets().flatMap(store -> store.read(secretKey(status.deviceId())))
                .filter(expected -> constantTimeEquals(expected, presentedToken)).isPresent();
    }

    @Override
    public KoboSyncBatch reconcileAndLoad(Stream<KoboBookProjection> currentLibrary,
                                          KoboSyncCursor after,
                                          int limit) {
        Objects.requireNonNull(currentLibrary, "currentLibrary");
        KoboDeviceStatus device = status();
        if (!device.enabled()) throw new IllegalStateException("Kobo sync is not enabled");
        int bounded = Math.max(1, Math.min(500, limit));
        long afterValue = after == null ? 0L : after.value();
        return CollectionTransactionExecutor.execute(collections, () -> {
            JdbcTemplate jdbc = jdbc();
            ensureMeta(device.deviceId());
            long generation = jdbc.queryForObject(
                    "UPDATE kobo_sync_meta SET generation=generation+1 WHERE device_id=? RETURNING generation",
                    Long.class, device.deviceId());

            currentLibrary.forEach(book -> reconcileOne(jdbc, device.deviceId(), generation, book));

            List<String> removed = jdbc.query("""
                    SELECT book_id FROM kobo_sync_books
                     WHERE device_id=? AND present=1 AND seen_generation<>?
                     ORDER BY book_id
                    """, (rs, rowNum) -> rs.getString(1), device.deviceId(), generation);
            for (String bookId : removed) {
                long cursor = nextCursor(jdbc, device.deviceId());
                jdbc.update("""
                        UPDATE kobo_sync_books
                           SET present=0,change_kind='REMOVE',cursor=?,seen_generation=?
                         WHERE device_id=? AND book_id=?
                        """, cursor, generation, device.deviceId(), bookId);
            }

            List<StoredChange> rows = jdbc.query("""
                    SELECT cursor,book_id,revision,present,change_kind,title,authors,series,sequence_number,
                           artifact_id,artifact_revision,format,download_name,modified_at
                      FROM kobo_sync_books
                     WHERE device_id=? AND cursor>?
                     ORDER BY cursor
                     LIMIT ?
                    """, (rs, rowNum) -> new StoredChange(
                            rs.getLong("cursor"), rs.getString("book_id"), rs.getString("revision"),
                            rs.getInt("present") != 0, rs.getString("change_kind"), rs.getString("title"), rs.getString("authors"),
                            rs.getString("series"), (Integer) rs.getObject("sequence_number"),
                            rs.getString("artifact_id"), rs.getString("artifact_revision"), rs.getString("format"),
                            rs.getString("download_name"), parseInstant(rs.getString("modified_at"))),
                    device.deviceId(), afterValue, bounded + 1);
            boolean hasMore = rows.size() > bounded;
            if (hasMore) rows = new ArrayList<>(rows.subList(0, bounded));
            List<KoboLibraryChange> changes = rows.stream().map(StoredChange::toChange).toList();
            long next = rows.isEmpty() ? afterValue : rows.getLast().cursor();
            return new KoboSyncBatch(changes, new KoboSyncCursor(next), hasMore);
        });
    }

    @Override
    public long observeProgressVersion(String bookId, String canonicalRevision) {
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("bookId is required");
        String revision = canonicalRevision == null ? "" : canonicalRevision;
        KoboDeviceStatus device = status();
        if (!device.enabled()) return 0L;
        return CollectionTransactionExecutor.execute(collections, () -> {
            JdbcTemplate jdbc = jdbc();
            ensureMeta(device.deviceId());
            List<ProgressRow> rows = jdbc.query("""
                    SELECT canonical_revision,version FROM kobo_progress_versions
                     WHERE device_id=? AND book_id=?
                    """, (rs, rowNum) -> new ProgressRow(rs.getString(1), rs.getLong(2)), device.deviceId(), bookId);
            if (rows.isEmpty()) {
                jdbc.update("INSERT INTO kobo_progress_versions(device_id,book_id,canonical_revision,version) VALUES(?,?,?,1)",
                        device.deviceId(), bookId, revision);
                return 1L;
            }
            ProgressRow row = rows.getFirst();
            if (Objects.equals(row.revision(), revision)) return row.version();
            long next = row.version() + 1L;
            jdbc.update("UPDATE kobo_progress_versions SET canonical_revision=?,version=? WHERE device_id=? AND book_id=?",
                    revision, next, device.deviceId(), bookId);
            return next;
        });
    }

    @Override
    public void recordSyncSuccess(Instant when) {
        settings.put(LAST_SYNC, (when == null ? Instant.now() : when).toString());
        settings.remove(LAST_ERROR);
    }

    @Override
    public void recordSyncFailure(Instant when, String safeErrorCode) {
        settings.put(LAST_ERROR, safeErrorCode == null ? "sync_failed" : safeErrorCode);
    }

    private void reconcileOne(JdbcTemplate jdbc, String deviceId, long generation, KoboBookProjection book) {
        List<BookState> existing = jdbc.query("""
                SELECT revision,present,change_kind FROM kobo_sync_books WHERE device_id=? AND book_id=?
                """, (rs, rowNum) -> new BookState(rs.getString(1), rs.getInt(2) != 0, rs.getString(3)), deviceId, book.bookId());
        boolean isAdd = existing.isEmpty() || !existing.getFirst().present();
        boolean changed = isAdd || !Objects.equals(existing.getFirst().revision(), book.revision());
        String changeKind = isAdd ? "ADD" : changed ? "UPDATE" : existing.getFirst().changeKind();
        long cursor = changed ? nextCursor(jdbc, deviceId) : currentCursor(jdbc, deviceId, book.bookId());
        jdbc.update("""
                INSERT INTO kobo_sync_books(device_id,book_id,revision,present,change_kind,cursor,seen_generation,title,authors,series,
                    sequence_number,artifact_id,artifact_revision,format,download_name,modified_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(device_id,book_id) DO UPDATE SET
                    revision=excluded.revision,present=1,change_kind=excluded.change_kind,cursor=excluded.cursor,seen_generation=excluded.seen_generation,
                    title=excluded.title,authors=excluded.authors,series=excluded.series,sequence_number=excluded.sequence_number,
                    artifact_id=excluded.artifact_id,artifact_revision=excluded.artifact_revision,format=excluded.format,
                    download_name=excluded.download_name,modified_at=excluded.modified_at
                """, deviceId, book.bookId(), book.revision(), 1, changeKind, cursor, generation, book.title(), book.authors(),
                book.series(), book.sequenceNumber(), book.artifactId(), book.artifactRevision(), book.format(),
                book.downloadName(), book.modifiedAt().toString());
    }

    private long currentCursor(JdbcTemplate jdbc, String deviceId, String bookId) {
        try {
            Long value = jdbc.queryForObject("SELECT cursor FROM kobo_sync_books WHERE device_id=? AND book_id=?",
                    Long.class, deviceId, bookId);
            return value == null ? 0L : value;
        } catch (EmptyResultDataAccessException missing) {
            return 0L;
        }
    }

    private long nextCursor(JdbcTemplate jdbc, String deviceId) {
        Long value = jdbc.queryForObject(
                "UPDATE kobo_sync_meta SET cursor=cursor+1 WHERE device_id=? RETURNING cursor",
                Long.class, deviceId);
        if (value == null) throw new IllegalStateException("Cannot advance Kobo cursor");
        return value;
    }

    private void ensureMeta(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return;
        JdbcTemplate jdbc = jdbc();
        jdbc.update("INSERT OR IGNORE INTO kobo_sync_meta(device_id,cursor,generation) VALUES(?,0,0)", deviceId);
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate current = collections.getCurrentJdbcTemplate();
        if (current == null) throw new IllegalStateException("Current collection is not selected");
        return current;
    }

    private Optional<SecretStore> secrets() {
        Optional<SecretStore> current = secretStore.get();
        if (current != null) return current;
        SecretStoreContext context = new SecretStoreContext(AppPaths.configDir(), AppPaths.portableMode(),
                System.getProperty("os.name", ""), System.getProperty("user.name", "unknown"));
        Optional<SecretStore> opened = ServiceLoader.load(SecretStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(SecretStoreProvider::priority).reversed())
                .map(provider -> {
                    try { return provider.open(context); }
                    catch (RuntimeException ignored) { return Optional.<SecretStore>empty(); }
                })
                .flatMap(Optional::stream).findFirst();
        secretStore.compareAndSet(null, opened);
        return secretStore.get();
    }

    private static String secretKey(String deviceId) { return SECRET_PREFIX + deviceId; }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (RuntimeException ignored) { return null; }
    }

    private record BookState(String revision, boolean present, String changeKind) { }
    private record ProgressRow(String revision, long version) { }
    private record StoredChange(long cursor, String bookId, String revision, boolean present, String changeKind, String title, String authors,
                                String series, Integer sequenceNumber, String artifactId, String artifactRevision,
                                String format, String downloadName, Instant modifiedAt) {
        KoboLibraryChange toChange() {
            if (!present) return new KoboLibraryChange(cursor, KoboLibraryChange.Kind.REMOVE, null, bookId);
            KoboBookProjection projection = new KoboBookProjection(bookId, revision, title, authors, series,
                    sequenceNumber, artifactId, artifactRevision, format, downloadName, modifiedAt);
            return new KoboLibraryChange(cursor, "ADD".equals(changeKind) ? KoboLibraryChange.Kind.ADD : KoboLibraryChange.Kind.UPDATE, projection, bookId);
        }
    }
}
