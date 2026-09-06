package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded lifecycle for archive entries handed to external processes.
 *
 * <p>Files from a previous JVM are treated as crash leftovers and removed on startup. Files owned by the
 * current JVM are protected while a tracked process is alive. Desktop.open() does not expose a Process handle,
 * so those leases may be retained until the next application startup rather than risking premature deletion.</p>
 */
@Component
public class ExternalReaderMaterializationCache implements AutoCloseable, InitializingBean, DisposableBean {
    static final long DEFAULT_MAX_BYTES = 1024L * 1024L * 1024L; // 1 GiB total cache ceiling
    static final Duration DEFAULT_MAX_AGE = Duration.ofHours(24);

    private final Path directory;
    private final long maxBytes;
    private final long maxFileBytes;
    private final Duration maxAge;
    private final Clock clock;
    private final Set<Path> active = new HashSet<>();
    private final Set<Path> retainUntilRestart = new HashSet<>();
    private final Object lock = new Object();
    private volatile boolean initialized;

    public ExternalReaderMaterializationCache() {
        this(AppPaths.cacheDir().resolve("external-reader"), DEFAULT_MAX_BYTES,
                ArchiveSafetyLimits.MAX_ENTRY_BYTES, DEFAULT_MAX_AGE, Clock.systemUTC());
    }

    ExternalReaderMaterializationCache(Path directory, long maxBytes, long maxFileBytes, Duration maxAge, Clock clock) {
        this.directory = directory.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.maxFileBytes = maxFileBytes;
        this.maxAge = maxAge == null ? DEFAULT_MAX_AGE : maxAge;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void initialize() {
        synchronized (lock) {
            if (initialized) return;
            try {
                Files.createDirectories(directory);
                cleanupCrashLeftoversLocked();
                cleanupInactiveLocked();
            } catch (IOException ignored) {
                // Best-effort startup cleanup. Materialization itself will surface a useful IOException later.
            }
            initialized = true;
        }
    }

    public Lease materialize(InputStream input, String extension) throws IOException {
        if (input == null) throw new IllegalArgumentException("Input stream is required");
        ensureInitialized();
        synchronized (lock) {
            Files.createDirectories(directory);
            cleanupInactiveLocked();
            long base = totalSizeLocked();
            if (base >= maxBytes) throw new IOException("External-reader cache is full");

            Path target = newTarget(extension);
            boolean success = false;
            long copied = 0;
            try (var out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = input.read(buffer)) >= 0;) {
                    if (n == 0) continue;
                    copied += n;
                    if (copied > maxFileBytes) throw new IOException("Materialized book exceeds the per-file cache limit");
                    if (base + copied > maxBytes) throw new IOException("External-reader cache size limit exceeded");
                    out.write(buffer, 0, n);
                }
                success = true;
            } finally {
                if (!success) Files.deleteIfExists(target);
            }
            Files.setLastModifiedTime(target, FileTime.from(clock.instant()));
            active.add(target);
            return new Lease(this, target);
        }
    }

    /** Moves/copies an already temporary resolved book into the managed external-reader cache. */
    public Lease adopt(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Temporary book file is missing");
        }
        ensureInitialized();
        synchronized (lock) {
            Files.createDirectories(directory);
            cleanupInactiveLocked();
            long size = Files.size(source);
            if (size > maxFileBytes) throw new IOException("Materialized book exceeds the per-file cache limit");
            if (totalSizeLocked() + size > maxBytes) throw new IOException("External-reader cache size limit exceeded");

            Path target = newTarget(extension(source.getFileName() == null ? "" : source.getFileName().toString()));
            try {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.FileSystemException moveFailure) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(source);
                }
            } catch (IOException e) {
                Files.deleteIfExists(target);
                throw e;
            }
            Files.setLastModifiedTime(target, FileTime.from(clock.instant()));
            active.add(target);
            return new Lease(this, target);
        }
    }

    /** Test/maintenance hook: enforce TTL/size bounds without touching active current-session leases. */
    public void cleanupInactive() {
        ensureInitialized();
        synchronized (lock) {
            try { cleanupInactiveLocked(); } catch (IOException ignored) { }
        }
    }

    Path directory() { return directory; }

    private void ensureInitialized() {
        if (!initialized) initialize();
    }

    private void cleanupCrashLeftoversLocked() throws IOException {
        if (!Files.isDirectory(directory)) return;
        for (Path file : cacheFilesLocked()) {
            // No file can be active before this JVM has issued a lease, therefore every pre-existing file is stale.
            Files.deleteIfExists(file);
        }
    }

    private void cleanupInactiveLocked() throws IOException {
        if (!Files.isDirectory(directory)) return;
        Instant cutoff = clock.instant().minus(maxAge);
        List<Path> candidates = new ArrayList<>();
        for (Path file : cacheFilesLocked()) {
            if (active.contains(file) || retainUntilRestart.contains(file)) continue;
            candidates.add(file);
            try {
                if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException ignored) { }
        }

        long total = totalSizeLocked();
        if (total <= maxBytes) return;
        candidates = cacheFilesLocked().stream()
                .filter(file -> !active.contains(file) && !retainUntilRestart.contains(file))
                .sorted(Comparator.comparingLong(this::modifiedQuietly))
                .toList();
        for (Path file : candidates) {
            if (total <= maxBytes) break;
            long size = sizeQuietly(file);
            try {
                if (Files.deleteIfExists(file)) total = Math.max(0, total - size);
            } catch (IOException ignored) { }
        }
    }

    private List<Path> cacheFilesLocked() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
        }
    }

    private long totalSizeLocked() throws IOException {
        long total = 0;
        for (Path file : cacheFilesLocked()) {
            long size = sizeQuietly(file);
            if (Long.MAX_VALUE - total < size) return Long.MAX_VALUE;
            total += size;
        }
        return total;
    }

    private Path newTarget(String extension) {
        String suffix = normalizeExtension(extension);
        return directory.resolve("book-" + UUID.randomUUID() + (suffix.isBlank() ? ".book" : "." + suffix));
    }

    private static String normalizeExtension(String value) {
        String ext = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (ext.startsWith(".")) ext = ext.substring(1);
        return ext.matches("[a-z0-9]{1,12}") ? ext : "book";
    }

    private static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    private long modifiedQuietly(Path file) {
        try { return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException e) { return Long.MIN_VALUE; }
    }

    private long sizeQuietly(Path file) {
        try { return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0; }
        catch (IOException e) { return 0; }
    }

    private void release(Path path, boolean preserve) {
        synchronized (lock) {
            if (preserve) {
                retainUntilRestart.add(path);
                active.add(path);
                return;
            }
            active.remove(path);
            retainUntilRestart.remove(path);
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
        }
    }


    @Override
    public void afterPropertiesSet() {
        initialize();
    }

    @Override
    public void destroy() {
        close();
    }

    @Override
    public void close() {
        synchronized (lock) {
            // Do not delete active/untracked Desktop files during application shutdown: the external reader may
            // outlive MyHomeLib. They intentionally become crash leftovers and are removed at the next startup.
            try { cleanupInactiveLocked(); } catch (IOException ignored) { }
        }
    }

    public static final class Lease implements AutoCloseable {
        private final ExternalReaderMaterializationCache owner;
        private final Path path;
        private final AtomicInteger holds = new AtomicInteger(1);
        private final AtomicBoolean ownerClosed = new AtomicBoolean(false);
        private final AtomicBoolean preserveUntilRestart = new AtomicBoolean(false);

        private Lease(ExternalReaderMaterializationCache owner, Path path) {
            this.owner = owner;
            this.path = path;
        }

        public Path path() { return path; }

        /** Keep one hold for the process. The file is removed only after every tracked process and the owner finish. */
        public void retainUntil(Process process) {
            if (process == null) return;
            if (ownerClosed.get()) throw new IllegalStateException("Materialized book lease is already closed");
            holds.incrementAndGet();
            try {
                process.onExit().whenComplete((ignored, failure) -> releaseOne());
            } catch (RuntimeException e) {
                // Registering the lifecycle callback itself can fail for a broken/custom Process implementation.
                // Never leak the extra hold in that case.
                releaseOne();
                throw e;
            }
        }

        /** Use when no Process handle is available (for example Desktop.open). Cleanup is deferred to next startup. */
        public void keepUntilNextStartup() {
            preserveUntilRestart.set(true);
            close();
        }

        @Override
        public void close() {
            if (ownerClosed.compareAndSet(false, true)) releaseOne();
        }

        private void releaseOne() {
            int remaining = holds.decrementAndGet();
            if (remaining == 0) owner.release(path, preserveUntilRestart.get());
            if (remaining < 0) throw new IllegalStateException("Materialized book lease released too many times");
        }
    }
}
