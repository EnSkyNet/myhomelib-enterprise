package com.myhomelibcorp.infrastructure.cover;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.ZipCharsetSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Multi-format archive reader used by the catalogue, cover loader, exporter and Reader.
 *
 * <p>The historical class name is kept to avoid breaking Spring wiring, however the
 * implementation supports ZIP/FB2ZIP/CBZ/JAR, 7z and RAR. ZIP and RAR entries are
 * exposed as live streams whose close() also closes the underlying archive. 7z is
 * sequential, therefore a requested entry is spooled to a temporary file and the
 * temporary file is deleted when the returned stream is closed.</p>
 */
@Component
@Slf4j
public class ZipArchiveReader implements ArchiveReader {


    @Override
    public boolean isArchive(Path file) {
        return file != null && isArchiveName(file.getFileName().toString());
    }

    public boolean isArchiveName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip")
                || lower.endsWith(".fb2zip")
                || lower.endsWith(".fb2.zip")
                || lower.endsWith(".cbz")
                || lower.endsWith(".jar")
                || lower.endsWith(".7z")
                || lower.endsWith(".rar") || lower.endsWith(".cbr")
                || lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")
                || lower.endsWith(".tar.xz") || lower.endsWith(".txz") || lower.endsWith(".cpio");
    }

    @Override
    public List<String> listEntries(Path archivePath) {
        if (archivePath == null || !Files.isRegularFile(archivePath)) return List.of();
        String lower = archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".7z")) return list7z(archivePath);
            if (lower.endsWith(".rar") || lower.endsWith(".cbr")) return listRar(archivePath);
            if (isStreamArchiveName(lower)) return listStreamArchive(archivePath);
            return listZip(archivePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати архів: " + archivePath, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Не вдалося прочитати архів: " + archivePath, e);
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося прочитати архів: " + archivePath, e);
        }
    }

    @Override
    public Optional<InputStream> readEntry(Path archivePath, String entryName) {
        if (archivePath == null || entryName == null || entryName.isBlank() || !Files.isRegularFile(archivePath)) {
            return Optional.empty();
        }
        String lower = archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".7z")) return read7zEntry(archivePath, entryName);
            if (lower.endsWith(".rar") || lower.endsWith(".cbr")) return readRarEntry(archivePath, entryName);
            if (isStreamArchiveName(lower)) return readStreamArchiveEntry(archivePath, entryName);
            return readZipEntry(archivePath, entryName);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати запис '" + entryName + "' з " + archivePath, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося прочитати запис '" + entryName + "' з " + archivePath, e);
        }
    }

    @Override
    public Optional<InputStream> findFirstEntry(Path archivePath, Predicate<String> filter) {
        if (archivePath == null || filter == null || !Files.isRegularFile(archivePath)) return Optional.empty();
        String lower = archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".7z")) return findFirst7zEntry(archivePath, filter);
            if (lower.endsWith(".rar") || lower.endsWith(".cbr")) return findFirstRarEntry(archivePath, filter);
            if (isStreamArchiveName(lower)) return findFirstStreamArchiveEntry(archivePath, filter);
            return findFirstZipEntry(archivePath, filter);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося знайти запис у архіві: " + archivePath, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося знайти запис у архіві: " + archivePath, e);
        }
    }

    @Override
    public boolean materializeEntry(Path archivePath, String entryName, Path target, long maxBytes,
                                    BooleanSupplier cancelled) throws IOException {
        if (archivePath == null || entryName == null || entryName.isBlank() || target == null
                || !Files.isRegularFile(archivePath)) return false;
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");

        long limit = Math.min(maxBytes, ArchiveSafetyLimits.MAX_ENTRY_BYTES);
        checkCancelled(cancelled);
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) throw new IOException("Materialization target has no parent: " + target);
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, ".mhl-archive-materialize-", ".part");
        boolean published = false;
        try {
            String lower = archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean found;
            try {
                if (lower.endsWith(".7z")) {
                    found = materialize7zEntry(archivePath, entryName, staging, limit, cancelled);
                } else if (lower.endsWith(".rar") || lower.endsWith(".cbr")) {
                    found = materializeRarEntry(archivePath, entryName, staging, limit, cancelled);
                } else if (isStreamArchiveName(lower)) {
                    found = materializeStreamArchiveEntry(archivePath, entryName, staging, limit, cancelled);
                } else {
                    found = materializeZipEntry(archivePath, entryName, staging, limit, cancelled);
                }
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Не вдалося матеріалізувати запис '" + entryName + "' з " + archivePath, e);
            }
            if (!found) return false;
            checkCancelled(cancelled);
            publishStaging(staging, absoluteTarget);
            published = true;
            return true;
        } finally {
            if (!published) Files.deleteIfExists(staging);
        }
    }

    private boolean materializeZipEntry(Path path, String requestedName, Path staging, long limit,
                                        BooleanSupplier cancelled) throws IOException {
        try (ZipFile zip = ZipCharsetSupport.open(path)) {
            ZipEntry entry = findZipEntryChecked(zip, requestedName);
            if (entry == null || entry.isDirectory()) return false;
            validateZipEntry(entry, limit);
            try (InputStream in = zip.getInputStream(entry);
                 OutputStream out = Files.newOutputStream(staging, StandardOpenOption.TRUNCATE_EXISTING)) {
                copyBounded(in, out, limit, cancelled, "ZIP entry");
            }
            return true;
        }
    }

    private boolean materialize7zEntry(Path path, String requestedName, Path staging, long limit,
                                       BooleanSupplier cancelled) throws IOException {
        try (SevenZFile sevenZ = SevenZFile.builder()
                .setFile(path.toFile())
                .setMaxMemoryLimitKiB(ArchiveSafetyLimits.SEVEN_Z_MEMORY_LIMIT_KIB)
                .get()) {
            SevenZArchiveEntry entry;
            int count = 0;
            while ((entry = sevenZ.getNextEntry()) != null) {
                checkCancelled(cancelled);
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("7z contains too many entries");
                if (entry.isDirectory() || entry.getName() == null || !sameEntry(entry.getName(), requestedName)) continue;
                if (declaredTooLarge(entry.getSize(), limit)) throw new IOException("7z entry is too large: " + entry.getSize());
                try (OutputStream out = Files.newOutputStream(staging, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    int read;
                    while ((read = sevenZ.read(buffer, 0, buffer.length)) >= 0) {
                        checkCancelled(cancelled);
                        if (read == 0) continue;
                        total = accountBytes(total, read, limit, "7z entry");
                        out.write(buffer, 0, read);
                    }
                }
                return true;
            }
            return false;
        }
    }

    private boolean materializeRarEntry(Path path, String requestedName, Path staging, long limit,
                                        BooleanSupplier cancelled) throws Exception {
        try (Archive archive = new Archive(path.toFile())) {
            if (archive.isPasswordProtected()) {
                throw new IOException("RAR archive is password-protected; configure/extract it before import");
            }
            int count = 0;
            for (FileHeader header : archive.getFileHeaders()) {
                checkCancelled(cancelled);
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("RAR contains too many entries");
                if (header.isDirectory() || header.getFileName() == null || !sameEntry(header.getFileName(), requestedName)) continue;
                try (InputStream in = archive.getInputStream(header);
                     OutputStream out = Files.newOutputStream(staging, StandardOpenOption.TRUNCATE_EXISTING)) {
                    copyBounded(in, out, limit, cancelled, "RAR entry");
                }
                return true;
            }
            return false;
        }
    }

    private boolean materializeStreamArchiveEntry(Path path, String requestedName, Path staging, long limit,
                                                  BooleanSupplier cancelled) throws Exception {
        try (ArchiveInputStream<?> in = openStreamArchive(path)) {
            ArchiveEntry entry;
            int count = 0;
            while ((entry = in.getNextEntry()) != null) {
                checkCancelled(cancelled);
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("Archive contains too many entries");
                if (entry.isDirectory() || entry.getName() == null || !sameEntry(entry.getName(), requestedName)) continue;
                if (declaredTooLarge(entry.getSize(), limit)) throw new IOException("Archive entry is too large: " + entry.getSize());
                try (OutputStream out = Files.newOutputStream(staging, StandardOpenOption.TRUNCATE_EXISTING)) {
                    copyBounded(in, out, limit, cancelled, "Archive entry");
                }
                return true;
            }
            return false;
        }
    }

    private Optional<InputStream> findFirstZipEntry(Path path, Predicate<String> filter) throws IOException {
        ZipFile zip = ZipCharsetSupport.open(path);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                    throw new IOException("ZIP contains too many entries");
                }
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName() == null || !filter.test(entry.getName())) continue;
                if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) {
                    throw new IOException("ZIP entry is too large: " + entry.getSize());
                }
                long compressed = entry.getCompressedSize();
                long size = entry.getSize();
                if (compressed > 0 && size > 0
                        && size / Math.max(1, compressed) > ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) {
                    throw new IOException("ZIP entry has suspicious compression ratio: " + entry.getName());
                }
                InputStream delegate = zip.getInputStream(entry);
                ZipFile owner = zip;
                return Optional.of(boundedOwnerStream(delegate, owner::close, "ZIP entry"));
            }
            zip.close();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            try { zip.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    private List<String> listZip(Path path) throws IOException {
        try (ZipFile zip = ZipCharsetSupport.open(path)) {
            List<String> result = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                    throw new IOException("ZIP contains too many entries");
                }
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) result.add(entry.getName());
            }
            return result;
        }
    }

    private Optional<InputStream> readZipEntry(Path path, String requestedName) throws IOException {
        ZipFile zip = ZipCharsetSupport.open(path);
        try {
            ZipEntry entry = findZipEntry(zip, requestedName);
            if (entry == null || entry.isDirectory()) {
                zip.close();
                return Optional.empty();
            }
            if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) {
                zip.close();
                throw new IOException("ZIP entry is too large: " + entry.getSize());
            }
            long compressed = entry.getCompressedSize();
            long size = entry.getSize();
            if (compressed > 0 && size > 0
                    && size / Math.max(1, compressed) > ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) {
                zip.close();
                throw new IOException("ZIP entry has suspicious compression ratio: " + entry.getName());
            }
            InputStream delegate = zip.getInputStream(entry);
            ZipFile owner = zip;
            return Optional.of(boundedOwnerStream(delegate, owner::close, "ZIP entry"));
        } catch (IOException | RuntimeException e) {
            try { zip.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    private ZipEntry findZipEntry(ZipFile zip, String requestedName) {
        ZipEntry direct = zip.getEntry(requestedName);
        if (direct != null) return direct;
        String normalized = normalizeEntryName(requestedName);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int count = 0;
        while (entries.hasMoreElements()) {
            if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) return null;
            ZipEntry entry = entries.nextElement();
            if (normalizeEntryName(entry.getName()).equalsIgnoreCase(normalized)) return entry;
        }
        return null;
    }

    private List<String> list7z(Path path) throws IOException {
        List<String> result = new ArrayList<>();
        try (SevenZFile sevenZ = SevenZFile.builder()
                .setFile(path.toFile())
                .setMaxMemoryLimitKiB(ArchiveSafetyLimits.SEVEN_Z_MEMORY_LIMIT_KIB)
                .get()) {
            SevenZArchiveEntry entry;
            int count = 0;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("7z contains too many entries");
                if (!entry.isDirectory() && entry.getName() != null) result.add(entry.getName());
            }
        }
        return result;
    }

    private Optional<InputStream> read7zEntry(Path path, String requestedName) throws IOException {
        return findFirst7zEntry(path, name -> sameEntry(name, requestedName));
    }

    private Optional<InputStream> findFirst7zEntry(Path path, Predicate<String> filter) throws IOException {
        Path temp = null;
        try (SevenZFile sevenZ = SevenZFile.builder()
                .setFile(path.toFile())
                .setMaxMemoryLimitKiB(ArchiveSafetyLimits.SEVEN_Z_MEMORY_LIMIT_KIB)
                .get()) {
            SevenZArchiveEntry entry;
            int count = 0;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("7z contains too many entries");
                if (entry.isDirectory() || entry.getName() == null || !filter.test(entry.getName())) continue;
                if (entry.getSize() > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                    throw new IOException("7z entry is too large: " + entry.getSize());
                }
                temp = Files.createTempFile("myhomelib-7z-", safeSuffix(entry.getName()));
                try (var out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    int read;
                    while ((read = sevenZ.read(buffer, 0, buffer.length)) > 0) {
                        total += read;
                        if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new IOException("7z entry exceeds safety limit");
                        out.write(buffer, 0, read);
                    }
                }
                return Optional.of(deleteOnClose(temp));
            }
        } catch (Exception e) {
            if (temp != null) Files.deleteIfExists(temp);
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
        return Optional.empty();
    }

    private List<String> listRar(Path path) throws Exception {
        try (Archive archive = new Archive(path.toFile())) {
            if (archive.isPasswordProtected()) {
                throw new IOException("RAR archive is password-protected; configure/extract it before import");
            }
            List<String> result = new ArrayList<>();
            int count = 0;
            for (FileHeader header : archive.getFileHeaders()) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("RAR contains too many entries");
                if (!header.isDirectory() && header.getFileName() != null) result.add(header.getFileName());
            }
            return result;
        }
    }

    private Optional<InputStream> readRarEntry(Path path, String requestedName) throws Exception {
        return findFirstRarEntry(path, name -> sameEntry(name, requestedName));
    }

    private Optional<InputStream> findFirstRarEntry(Path path, Predicate<String> filter) throws Exception {
        Archive archive = new Archive(path.toFile());
        try {
            if (archive.isPasswordProtected()) {
                throw new IOException("RAR archive is password-protected; configure/extract it before import");
            }
            int count = 0;
            for (FileHeader header : archive.getFileHeaders()) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("RAR contains too many entries");
                if (header.isDirectory() || header.getFileName() == null || !filter.test(header.getFileName())) continue;
                InputStream delegate = archive.getInputStream(header);
                return Optional.of(boundedOwnerStream(delegate, archive::close, "RAR entry"));
            }
            archive.close();
            return Optional.empty();
        } catch (Exception e) {
            try { archive.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    private boolean isStreamArchiveName(String lower) {
        return lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")
                || lower.endsWith(".tar.xz") || lower.endsWith(".txz") || lower.endsWith(".cpio");
    }

    private ArchiveInputStream<?> openStreamArchive(Path path) throws Exception {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        InputStream raw = new BufferedInputStream(Files.newInputStream(path), 64 * 1024);
        InputStream source = raw;
        try {
            if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
                source = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, raw, true);
            } else if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")) {
                source = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, raw, true);
            } else if (lower.endsWith(".tar.xz") || lower.endsWith(".txz")) {
                source = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, raw, true);
            }
            String type = lower.endsWith(".cpio") ? ArchiveStreamFactory.CPIO : ArchiveStreamFactory.TAR;
            return new ArchiveStreamFactory().createArchiveInputStream(type, source);
        } catch (Exception e) {
            try { source.close(); } catch (Exception ignored) { }
            if (source != raw) try { raw.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    private List<String> listStreamArchive(Path path) throws Exception {
        List<String> result = new ArrayList<>();
        try (ArchiveInputStream<?> in = openStreamArchive(path)) {
            ArchiveEntry entry;
            int count = 0;
            while ((entry = in.getNextEntry()) != null) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("Archive contains too many entries");
                if (!entry.isDirectory() && entry.getName() != null) result.add(entry.getName());
            }
        }
        return result;
    }

    private Optional<InputStream> readStreamArchiveEntry(Path path, String requestedName) throws Exception {
        return findFirstStreamArchiveEntry(path, name -> sameEntry(name, requestedName));
    }

    private Optional<InputStream> findFirstStreamArchiveEntry(Path path, Predicate<String> filter) throws Exception {
        Path temp = null;
        try (ArchiveInputStream<?> in = openStreamArchive(path)) {
            ArchiveEntry entry;
            int count = 0;
            while ((entry = in.getNextEntry()) != null) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("Archive contains too many entries");
                if (entry.isDirectory() || entry.getName() == null || !filter.test(entry.getName())) continue;
                if (entry.getSize() > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new IOException("Archive entry is too large");
                temp = Files.createTempFile("myhomelib-archive-", safeSuffix(entry.getName()));
                try (var out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0; int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new IOException("Archive entry exceeds safety limit");
                        out.write(buffer, 0, n);
                    }
                }
                return Optional.of(deleteOnClose(temp));
            }
        } catch (Exception e) {
            if (temp != null) Files.deleteIfExists(temp);
            throw e;
        }
        return Optional.empty();
    }


    private ZipEntry findZipEntryChecked(ZipFile zip, String requestedName) throws IOException {
        ZipEntry direct = zip.getEntry(requestedName);
        if (direct != null) return direct;
        String normalized = normalizeEntryName(requestedName);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int count = 0;
        while (entries.hasMoreElements()) {
            if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("ZIP contains too many entries");
            ZipEntry entry = entries.nextElement();
            if (normalizeEntryName(entry.getName()).equalsIgnoreCase(normalized)) return entry;
        }
        return null;
    }

    private static boolean declaredTooLarge(long declaredSize, long limit) {
        return declaredSize >= 0 && declaredSize > limit;
    }

    private static long accountBytes(long current, long delta, long limit, String label) throws IOException {
        long total = current + delta;
        if (total < current || total > limit) {
            throw new IOException(label + " exceeds safety limit of " + limit + " bytes");
        }
        return total;
    }

    private static long copyBounded(InputStream in, OutputStream out, long limit, BooleanSupplier cancelled,
                                    String label) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            checkCancelled(cancelled);
            if (read == 0) continue;
            total = accountBytes(total, read, limit, label);
            out.write(buffer, 0, read);
        }
        checkCancelled(cancelled);
        return total;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new InterruptedIOException("Archive materialization cancelled");
        }
    }

    private static void publishStaging(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateZipEntry(ZipEntry entry, long limit) throws IOException {
        if (declaredTooLarge(entry.getSize(), limit)) {
            throw new IOException("ZIP entry is too large: " + entry.getSize());
        }
        long compressed = entry.getCompressedSize();
        long size = entry.getSize();
        if (compressed > 0 && size > 0
                && size / Math.max(1, compressed) > ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) {
            throw new IOException("ZIP entry has suspicious compression ratio: " + entry.getName());
        }
    }


    @FunctionalInterface
    private interface CloseAction {
        void close() throws Exception;
    }

    /** Enforces the same actual-byte ceiling for live ZIP/RAR streams as spooled readers. */
    private InputStream boundedOwnerStream(InputStream delegate, CloseAction ownerClose, String label) {
        return new FilterInputStream(delegate) {
            private long total;
            private boolean closed;

            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0) account(1);
                return value;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) account(n);
                return n;
            }

            private void account(long n) throws IOException {
                total += n;
                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                    try { close(); } catch (IOException ignored) { }
                    throw new IOException(label + " exceeds safety limit of " + ArchiveSafetyLimits.MAX_ENTRY_BYTES + " bytes");
                }
            }

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                IOException first = null;
                try { super.close(); } catch (IOException e) { first = e; }
                try { ownerClose.close(); } catch (Exception e) {
                    if (first == null) first = e instanceof IOException io ? io : new IOException(e);
                    else first.addSuppressed(e);
                }
                if (first != null) throw first;
            }
        };
    }

    private InputStream deleteOnClose(Path temp) throws IOException {
        InputStream delegate = Files.newInputStream(temp);
        return new FilterInputStream(delegate) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        };
    }

    private boolean sameEntry(String a, String b) {
        return normalizeEntryName(a).equalsIgnoreCase(normalizeEntryName(b));
    }

    private String normalizeEntryName(String name) {
        return name == null ? "" : name.replace('\\', '/').replaceAll("^/+", "");
    }

    private String safeSuffix(String name) {
        String fileName;
        try {
            fileName = Path.of(normalizeEntryName(name)).getFileName().toString();
        } catch (Exception e) {
            fileName = "entry.bin";
        }
        fileName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        return "-" + (fileName.isBlank() ? "entry.bin" : fileName);
    }
}
