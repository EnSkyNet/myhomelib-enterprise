package com.myhomelibcorp.application.usecase.conversion;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.book.BookArtifactState;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * MHL-506 provider-neutral conversion job orchestration.
 *
 * <p>The application owns temp files, limits, crash-safe finalization and artifact
 * registration. Converter providers only transform one bounded source stream into the
 * staged target supplied by the application.</p>
 */
@Component
@RequiredArgsConstructor
public class ConvertBookUseCase {

    public static final long DEFAULT_MAX_INPUT_BYTES = 512L * 1024L * 1024L;
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 1024L * 1024L * 1024L;

    private final BookQueryRepository books;
    private final List<BookConverter> converters;
    private final BookResourcePort resources;
    private final CommittedCatalogMutationService mutations;

    public record Request(BookId bookId,
                          String targetFormat,
                          Path outputDirectory,
                          boolean makePreferred,
                          long maxInputBytes,
                          long maxOutputBytes,
                          BooleanSupplier cancelled) {
        public Request {
            Objects.requireNonNull(bookId, "bookId");
            targetFormat = BookConversionCapability.normalizeFormat(targetFormat);
            if (targetFormat.isBlank()) throw new IllegalArgumentException("Target format is required");
            Objects.requireNonNull(outputDirectory, "outputDirectory");
            maxInputBytes = maxInputBytes <= 0 ? DEFAULT_MAX_INPUT_BYTES : maxInputBytes;
            maxOutputBytes = maxOutputBytes <= 0 ? DEFAULT_MAX_OUTPUT_BYTES : maxOutputBytes;
            cancelled = cancelled == null ? () -> false : cancelled;
        }

        public static Request withDefaults(BookId bookId, String targetFormat, Path outputDirectory) {
            return new Request(bookId, targetFormat, outputDirectory, false,
                    DEFAULT_MAX_INPUT_BYTES, DEFAULT_MAX_OUTPUT_BYTES, () -> false);
        }
    }

    public record CapabilityView(String converterId,
                                 boolean available,
                                 BookConversionCapability capability) { }

    public record Result(String converterId,
                         BookArtifact artifact,
                         long durationMs) { }

    public List<CapabilityView> capabilityMatrix() {
        List<CapabilityView> matrix = new ArrayList<>();
        for (BookConverter converter : converters == null ? List.<BookConverter>of() : converters) {
            boolean available = safeAvailable(converter);
            for (BookConversionCapability capability : safeCapabilities(converter)) {
                matrix.add(new CapabilityView(converter.id(), available, capability));
            }
        }
        return List.copyOf(matrix);
    }

    public Result execute(Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        long startedAt = System.nanoTime();
        checkCancelled(request.cancelled());

        Book book = books.findById(request.bookId())
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + request.bookId()));
        SelectedJob job = selectJob(book, request.targetFormat());
        Source source = job.source();
        SelectedConverter selected = job.converter();
        if (source.sizeBytes() > 0 && source.sizeBytes() > request.maxInputBytes()) {
            throw new IllegalStateException("Conversion input exceeds limit: " + source.sizeBytes()
                    + " > " + request.maxInputBytes());
        }
        Path root = request.outputDirectory().toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new IllegalStateException("Conversion output directory is not writable: " + root);
        }

        String artifactId = "conversion:" + UUID.randomUUID();
        String extension = safeExtension(selected.capability().targetExtension());
        String finalName = safeBaseName(book.getTitle(), book.getId()) + "-"
                + artifactId.substring(artifactId.length() - 8) + extension;
        Path finalFile = root.resolve(finalName).normalize();
        if (!finalFile.startsWith(root)) throw new IllegalArgumentException("Unsafe conversion target path");
        Path staged = Files.createTempFile(root, ".mhl-convert-", extension);
        boolean registered = false;
        boolean finalMoved = false;
        try {
            checkCancelled(request.cancelled());
            try (InputStream raw = source.open();
                 InputStream bounded = new CancellableBoundedInputStream(raw, request.maxInputBytes(), request.cancelled())) {
                BookConversionContext context = new BookConversionContext(
                        book, source.format(), request.targetFormat(), bounded, staged,
                        request.cancelled(), request.maxOutputBytes());
                selected.converter().convert(context);
            }
            checkCancelled(request.cancelled());
            validateOutput(staged, request.maxOutputBytes());
            moveAtomically(staged, finalFile);
            finalMoved = true;
            validateOutput(finalFile, request.maxOutputBytes());

            long size = Files.size(finalFile);
            String sha256 = sha256(finalFile);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("conversion.provider", selected.converter().id());
            metadata.put("conversion.sourceFormat", source.format());
            metadata.put("conversion.targetFormat", request.targetFormat());
            metadata.put("conversion.createdAt", Instant.now().toString());
            if (source.artifactId() != null && !source.artifactId().isBlank()) {
                metadata.put("conversion.sourceArtifactId", source.artifactId());
            }

            BookArtifact artifact = BookArtifact.builder()
                    .id(artifactId)
                    .sourceId("conversion:" + selected.converter().id())
                    .name(finalName)
                    .mediaType(mediaType(request.targetFormat()))
                    .format(request.targetFormat())
                    .file(new BookFile(finalName, "", "", size, root.toString()))
                    .sha256(sha256)
                    .contentFingerprint(sha256)
                    .remote(false)
                    .local(true)
                    .state(BookArtifactState.AVAILABLE)
                    .metadata(metadata)
                    .build();

            mutations.upsertArtifact(book.getId(), artifact, request.makePreferred());
            registered = true;

            long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            return new Result(selected.converter().id(), artifact, durationMs);
        } finally {
            Files.deleteIfExists(staged);
            if (finalMoved && !registered) Files.deleteIfExists(finalFile);
        }
    }

    private SelectedJob selectJob(Book book, String targetFormat) {
        List<Source> sources = sourceCandidates(book);
        for (Source source : sources) {
            for (BookConverter converter : converters == null ? List.<BookConverter>of() : converters) {
                if (!safeAvailable(converter) || !safeSupports(converter, book, source.format())) continue;
                for (BookConversionCapability capability : safeCapabilities(converter)) {
                    if (capability.produces(targetFormat) && capability.supportsSource(source.format())) {
                        return new SelectedJob(source, new SelectedConverter(converter, capability));
                    }
                }
            }
        }
        String formats = sources.stream().map(Source::format).distinct().toList().toString();
        throw new IllegalArgumentException("No converter for sources " + formats + " -> " + targetFormat);
    }

    private List<Source> sourceCandidates(Book book) {
        List<Source> result = new ArrayList<>();
        Optional<BookArtifact> preferred = book.getPreferredArtifact().filter(BookArtifact::isAvailable);
        preferred.ifPresent(artifact -> result.add(sourceFromArtifact(artifact)));
        for (BookArtifact artifact : book.getArtifacts()) {
            if (!artifact.isAvailable()) continue;
            if (preferred.isPresent() && preferred.get().getId().equals(artifact.getId())) continue;
            result.add(sourceFromArtifact(artifact));
        }
        if (result.isEmpty()) {
            String format = sourceFormat("", book.getFile());
            result.add(new Source(format, book.getFileSize(), null, () -> resources.readBookData(book)
                    .orElseThrow(() -> new IllegalArgumentException("Book source is not readable: " + book.getId()))));
        }
        return List.copyOf(result);
    }

    private Source sourceFromArtifact(BookArtifact artifact) {
        String format = sourceFormat(artifact.getFormat(), artifact.getFile());
        return new Source(format, artifact.getFile().getFileSize(), artifact.getId(), () -> resources.readBookData(
                artifact.getFile().getFileName(), artifact.getFile().getFolder(),
                artifact.getFile().getCollectionRoot(), artifact.getFile().getArchiveEntry())
                .orElseThrow(() -> new IllegalArgumentException("Source artifact is not readable: " + artifact.displayName())));
    }

    private List<BookConversionCapability> safeCapabilities(BookConverter converter) {
        try {
            var capabilities = converter.capabilities();
            return capabilities == null ? List.of() : capabilities.stream().filter(Objects::nonNull).toList();
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    private boolean safeAvailable(BookConverter converter) {
        try { return converter != null && converter.isAvailable(); }
        catch (RuntimeException failure) { return false; }
    }

    private boolean safeSupports(BookConverter converter, Book book, String sourceFormat) {
        try { return converter.supports(book, sourceFormat); }
        catch (RuntimeException failure) { return false; }
    }

    private static String sourceFormat(String explicit, BookFile file) {
        String normalized = BookConversionCapability.normalizeFormat(explicit);
        if (!normalized.isBlank()) return normalized;
        String name = file == null ? "" : (file.hasArchiveEntry() ? file.getArchiveEntry() : file.getFileName());
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fb2.zip")) return "fb2_zip";
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && dot + 1 < lower.length()
                ? BookConversionCapability.normalizeFormat(lower.substring(dot + 1)) : "";
    }

    private static String safeBaseName(String title, BookId id) {
        String base = title == null ? "" : title.trim();
        if (base.isBlank()) base = "book-" + id.asString();
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ").trim();
        if (base.equals(".") || base.equals("..") || base.isBlank()) base = "book-" + id.asString();
        if (base.length() > 96) base = base.substring(0, 96).trim();
        return base;
    }

    private static String safeExtension(String extension) {
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        if (!ext.startsWith(".")) ext = "." + ext;
        if (!ext.matches("\\.[a-z0-9][a-z0-9._-]{0,15}")) {
            throw new IllegalArgumentException("Unsafe conversion extension: " + extension);
        }
        return ext;
    }

    private static void validateOutput(Path file, long maxOutputBytes) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Converter did not create an output file");
        long size = Files.size(file);
        if (size <= 0) throw new IOException("Converter produced an empty output file");
        if (size > maxOutputBytes) {
            throw new IOException("Conversion output exceeds limit: " + size + " > " + maxOutputBytes);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String mediaType(String format) {
        return switch (BookConversionCapability.normalizeFormat(format)) {
            case "epub" -> "application/epub+zip";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "fb2" -> "application/x-fictionbook+xml";
            case "fb2_zip" -> "application/zip";
            case "mobi" -> "application/x-mobipocket-ebook";
            default -> "application/octet-stream";
        };
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
    }

    @FunctionalInterface
    private interface InputStreamFactory { InputStream open() throws Exception; }

    private record Source(String format, long sizeBytes, String artifactId, InputStreamFactory opener) {
        InputStream open() throws Exception { return opener.open(); }
    }

    private record SelectedConverter(BookConverter converter, BookConversionCapability capability) { }
    private record SelectedJob(Source source, SelectedConverter converter) { }

    /** Bounded source wrapper: providers cannot read past the job input limit and reads observe cancellation. */
    private static final class CancellableBoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private final BooleanSupplier cancelled;
        private long count;

        private CancellableBoundedInputStream(InputStream delegate, long maxBytes, BooleanSupplier cancelled) {
            super(delegate);
            this.maxBytes = maxBytes;
            this.cancelled = cancelled == null ? () -> false : cancelled;
        }

        @Override public int read() throws IOException {
            check();
            int value = super.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            check();
            long remaining = maxBytes - count;
            if (remaining <= 0) {
                int extra = super.read();
                if (extra < 0) return -1;
                count++;
                throw new IOException("Conversion input exceeds limit: " + maxBytes + " bytes");
            }
            int boundedLength = (int) Math.min(length, remaining);
            int read = super.read(buffer, offset, boundedLength);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(long delta) throws IOException {
            count += delta;
            if (count > maxBytes) throw new IOException("Conversion input exceeds limit: " + maxBytes + " bytes");
        }

        private void check() {
            if (cancelled.getAsBoolean()) throw new CancellationException("Book conversion cancelled");
        }
    }
}
