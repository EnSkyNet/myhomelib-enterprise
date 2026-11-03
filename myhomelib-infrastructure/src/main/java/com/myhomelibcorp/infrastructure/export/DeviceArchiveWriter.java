package com.myhomelibcorp.infrastructure.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.exporter.DeviceArchivePort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.SafeFileNameSupport;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streaming, bounded ZIP/7z output for an isolated device-export staging tree. */
@Component
public final class DeviceArchiveWriter implements DeviceArchivePort {
    private static final int MAX_ENTRIES = 10_000;
    private static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int BUFFER_BYTES = 64 * 1024;

    @Override
    public void pack(Path sourceRoot, Path stagedArchive, ExportRequest.ArchiveFormat format,
                     ExportRequest.CompressionLevel compression, BooleanSupplier cancelled) throws IOException {
        if (format == null || format == ExportRequest.ArchiveFormat.NONE) {
            throw new IOException("ZIP or 7z output format required");
        }
        if (sourceRoot == null || stagedArchive == null) throw new IOException("Missing archive path");
        Path root = SafeFilePublication.requireSafeDirectoryPath(sourceRoot.toAbsolutePath().normalize(),
                "Device archive source");
        Path target = stagedArchive.toAbsolutePath().normalize();
        SafeFilePublication.requireSafeDirectoryPath(target.getParent(), "Device archive staging parent");
        if (target.startsWith(root) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Archive target must be fresh and outside source tree");
        }
        List<Member> members = collect(root, cancelled);
        if (members.isEmpty()) throw new IOException("No staged book files");
        var strength = compression == null ? ExportRequest.CompressionLevel.MAXIMUM : compression;
        try {
            switch (format) {
                case ZIP -> zip(target, members, strength, cancelled);
                case SEVEN_Z -> sevenZ(target, members, strength, cancelled);
                default -> throw new IOException("Archive output format not supported");
            }
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(target); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static List<Member> collect(Path root, BooleanSupplier cancelled) throws IOException {
        List<Member> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long total = 0L;
        try (var stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                checkCancel(cancelled);
                if (path.equals(root)) continue;
                if (Files.isSymbolicLink(path)) throw new IOException("Symlinks are not allowed in export");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                Path safe = SafeFilePublication.requireSafeRegularFile(path, "Staged export book");
                String name = SafeFileNameSupport.validateRelativePath(root.relativize(safe))
                        .toString().replace('\\', '/');
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate archive entry");
                long size = Files.size(safe);
                if (size > MAX_BYTES || total > MAX_BYTES - size) throw new IOException("Archive exceeds size budget");
                total += size;
                if (result.size() >= MAX_ENTRIES) throw new IOException("Too many archive entries");
                result.add(new Member(safe, name, size));
            }
        }
        result.sort(Comparator.comparing(Member::name));
        return List.copyOf(result);
    }

    private static void zip(Path target, List<Member> members, ExportRequest.CompressionLevel level,
                            BooleanSupplier cancel) throws IOException {
        try (OutputStream out = SafeFilePublication.openFreshOutputStream(target, "Device ZIP");
             ZipOutputStream zip = new ZipOutputStream(out, java.nio.charset.StandardCharsets.UTF_8)) {
            zip.setLevel(switch (level) { case FAST -> 1; case NORMAL -> 6; case MAXIMUM -> 9; });
            for (Member member : members) {
                checkCancel(cancel);
                zip.putNextEntry(new ZipEntry(member.name()));
                try (InputStream in = Files.newInputStream(member.path(), LinkOption.NOFOLLOW_LINKS)) {
                    transfer(in, zip, member.size(), cancel);
                }
                zip.closeEntry();
            }
        }
    }

    private static void sevenZ(Path target, List<Member> members, ExportRequest.CompressionLevel level,
                               BooleanSupplier cancel) throws IOException {
        // Maximum uses a bounded 16 MiB LZMA2 dictionary to suit the 4 GiB Windows target.
        int dictionary = switch (level) {
            case FAST -> 1 << 20; case NORMAL -> 4 << 20; case MAXIMUM -> 16 << 20;
        };
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             SevenZOutputFile archive = new SevenZOutputFile(channel)) {
            archive.setContentMethods(List.of(new SevenZMethodConfiguration(SevenZMethod.LZMA2, dictionary)));
            byte[] buffer = new byte[BUFFER_BYTES];
            for (Member member : members) {
                checkCancel(cancel);
                archive.putArchiveEntry(archive.createArchiveEntry(member.path().toFile(), member.name()));
                try (InputStream in = Files.newInputStream(member.path(), LinkOption.NOFOLLOW_LINKS)) {
                    long remaining = member.size();
                    for (int count; (count = in.read(buffer)) >= 0;) {
                        checkCancel(cancel);
                        if (count == 0) continue;
                        if (count > remaining) throw new IOException("Export source grew while packing");
                        archive.write(buffer, 0, count);
                        remaining -= count;
                    }
                    if (remaining != 0) throw new IOException("Export source shrank while packing");
                }
                archive.closeArchiveEntry();
            }
        }
    }

    private static void transfer(InputStream in, OutputStream out, long size, BooleanSupplier cancel)
            throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = size;
        for (int n; (n = in.read(buffer)) >= 0;) {
            checkCancel(cancel);
            if (n == 0) continue;
            if (n > remaining) throw new IOException("Export source grew while packing");
            out.write(buffer, 0, n);
            remaining -= n;
        }
        if (remaining != 0) throw new IOException("Export source shrank while packing");
    }

    private static void checkCancel(BooleanSupplier cancelled) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new InterruptedIOException("Archive export cancelled");
        }
    }

    private record Member(Path path, String name, long size) { }
}
