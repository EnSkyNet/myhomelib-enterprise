package com.myhomelibcorp.infrastructure.cover;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
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
import java.io.BufferedInputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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


    private static final Charset[] ZIP_CHARSETS = {
            Charset.forName("UTF-8"),
            Charset.forName("CP866"),
            Charset.forName("Windows-1251"),
            Charset.forName("IBM-866"),
            Charset.forName("KOI8-R")
    };

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
        String lower = archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".7z")) return list7z(archivePath);
            if (lower.endsWith(".rar") || lower.endsWith(".cbr")) return listRar(archivePath);
            if (isStreamArchiveName(lower)) return listStreamArchive(archivePath);
            return listZip(archivePath);
        } catch (Exception e) {
            log.warn("Не вдалося прочитати архів {}: {}", archivePath, e.getMessage());
            return List.of();
        }
    }

    /**
     * Checks whether a named logical entry exists without extracting its payload.
     * ZIP uses the central directory directly; sequential archive formats fall back
     * to bounded entry enumeration. This is used by effective-local resolution so
     * an existing but incomplete/corrupt shared archive is not treated as a local book.
     */
    public boolean containsEntry(Path archivePath, String entryName) {
        if (archivePath == null || entryName == null || entryName.isBlank() || !Files.isRegularFile(archivePath)) {
            return false;
        }
        String lower = archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (!lower.endsWith(".7z") && !lower.endsWith(".rar") && !lower.endsWith(".cbr")
                    && !isStreamArchiveName(lower)) {
                for (Charset charset : ZIP_CHARSETS) {
                    try (ZipFile zip = new ZipFile(archivePath.toFile(), charset)) {
                        ZipEntry entry = findZipEntry(zip, entryName);
                        if (entry != null && !entry.isDirectory()) return true;
                    } catch (Exception ignored) {
                        // Try the next legacy ZIP charset.
                    }
                }
                return false;
            }
            return listEntries(archivePath).stream().anyMatch(name -> sameEntry(name, entryName));
        } catch (RuntimeException error) {
            log.debug("Не вдалося перевірити запис '{}' у {}: {}", entryName, archivePath, error.getMessage());
            return false;
        }
    }

    @Override
    public Optional<InputStream> readEntry(Path archivePath, String entryName) {
        if (archivePath == null || entryName == null || entryName.isBlank()) return Optional.empty();
        String lower = archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".7z")) return read7zEntry(archivePath, entryName);
            if (lower.endsWith(".rar") || lower.endsWith(".cbr")) return readRarEntry(archivePath, entryName);
            if (isStreamArchiveName(lower)) return readStreamArchiveEntry(archivePath, entryName);
            return readZipEntry(archivePath, entryName);
        } catch (Exception e) {
            log.warn("Не вдалося прочитати запис '{}' з {}: {}", entryName, archivePath, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<InputStream> findFirstEntry(Path archivePath, Predicate<String> filter) {
        if (archivePath == null || filter == null) return Optional.empty();
        for (String name : listEntries(archivePath)) {
            if (filter.test(name)) {
                Optional<InputStream> result = readEntry(archivePath, name);
                if (result.isPresent()) return result;
            }
        }
        return Optional.empty();
    }

    private List<String> listZip(Path path) throws IOException {
        Exception last = null;
        for (Charset charset : ZIP_CHARSETS) {
            try (ZipFile zip = new ZipFile(path.toFile(), charset)) {
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
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("ZIP cannot be opened with supported encodings", last);
    }

    private Optional<InputStream> readZipEntry(Path path, String requestedName) throws IOException {
        Exception last = null;
        for (Charset charset : ZIP_CHARSETS) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(path.toFile(), charset);
                ZipEntry entry = findZipEntry(zip, requestedName);
                if (entry == null || entry.isDirectory()) {
                    zip.close();
                    continue;
                }
                if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) {
                    zip.close();
                    throw new IOException("ZIP entry is too large: " + entry.getSize());
                }
                InputStream delegate = zip.getInputStream(entry);
                ZipFile owner = zip;
                return Optional.of(boundedOwnerStream(delegate, owner::close, "ZIP entry"));
            } catch (Exception e) {
                last = e;
                if (zip != null) try { zip.close(); } catch (Exception ignored) { }
            }
        }
        if (last != null) log.debug("ZIP entry lookup failed: {}", last.getMessage());
        return Optional.empty();
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
        Path temp = null;
        try (SevenZFile sevenZ = SevenZFile.builder()
                .setFile(path.toFile())
                .setMaxMemoryLimitKiB(ArchiveSafetyLimits.SEVEN_Z_MEMORY_LIMIT_KIB)
                .get()) {
            SevenZArchiveEntry entry;
            int count = 0;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("7z contains too many entries");
                if (entry.isDirectory() || entry.getName() == null) continue;
                if (!sameEntry(entry.getName(), requestedName)) continue;
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
        Archive archive = new Archive(path.toFile());
        try {
            if (archive.isPasswordProtected()) {
                throw new IOException("RAR archive is password-protected; configure/extract it before import");
            }
            int count = 0;
            for (FileHeader header : archive.getFileHeaders()) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("RAR contains too many entries");
                if (header.isDirectory() || header.getFileName() == null) continue;
                if (!sameEntry(header.getFileName(), requestedName)) continue;
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
        Path temp = null;
        try (ArchiveInputStream<?> in = openStreamArchive(path)) {
            ArchiveEntry entry;
            int count = 0;
            while ((entry = in.getNextEntry()) != null) {
                if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("Archive contains too many entries");
                if (entry.isDirectory() || entry.getName() == null || !sameEntry(entry.getName(), requestedName)) continue;
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
