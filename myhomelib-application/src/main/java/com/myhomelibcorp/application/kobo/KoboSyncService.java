package com.myhomelibcorp.application.kobo;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@Slf4j
public final class KoboSyncService {
    private final BookQueryRepository books;
    private final ReadingProgressRepository progress;
    private final KoboSyncPort port;
    private final ConvertBookUseCase conversions;

    public KoboSyncService(BookQueryRepository books, ReadingProgressRepository progress, KoboSyncPort port) {
        this(books, progress, port, null);
    }

    @Autowired
    public KoboSyncService(BookQueryRepository books, ReadingProgressRepository progress, KoboSyncPort port,
                           ConvertBookUseCase conversions) {
        this.books = Objects.requireNonNull(books, "books");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.port = Objects.requireNonNull(port, "port");
        this.conversions = conversions;
    }

    public KoboDeviceStatus status() { return port.status(); }
    public KoboCredentialIssue enableOrRotate() { return port.enableOrRotate(); }
    public void revoke() { port.revoke(); }
    public boolean authenticate(String token) { return port.authenticate(token); }

    public KoboSyncBatch synchronize(KoboSyncCursor cursor, int limit) {
        int bounded = Math.max(1, Math.min(500, limit));
        List<ConvertBookUseCase.CapabilityView> capabilities = conversionCapabilities();
        try (Stream<Book> stream = books.streamAll()) {
            Stream<KoboBookProjection> projections = stream
                    .filter(Objects::nonNull)
                    .filter(book -> !book.isDeleted())
                    .map(book -> projectInternal(book, capabilities))
                    .flatMap(Optional::stream);
            KoboSyncBatch batch = port.reconcileAndLoad(projections,
                    cursor == null ? KoboSyncCursor.initial() : cursor, bounded);
            port.recordSyncSuccess(Instant.now());
            return batch;
        } catch (RuntimeException failure) {
            port.recordSyncFailure(Instant.now(), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    public Optional<KoboBookProjection> book(String bookId) {
        if (bookId == null || bookId.isBlank()) return Optional.empty();

        final com.myhomelibcorp.domain.model.valueobject.BookId parsedId;
        try {
            parsedId = com.myhomelibcorp.domain.model.valueobject.BookId.fromString(bookId);
        } catch (IllegalArgumentException invalidId) {
            return Optional.empty();
        }

        return books.findById(parsedId)
                .filter(book -> !book.isDeleted())
                .flatMap(book -> projectInternal(book, conversionCapabilities()));
    }

    public Optional<KoboProgressState> progress(String bookId) {
        if (bookId == null || bookId.isBlank()) return Optional.empty();
        return progress.findByBookId(bookId)
                .map(value -> KoboProgressTranslator.toKobo(value,
                        port.observeProgressVersion(bookId, progressRevision(value))));
    }

    public KoboProgressState updateProgress(KoboProgressUpdate update) {
        Objects.requireNonNull(update, "update");
        ReadingProgressDto canonicalDto = progress.findByBookId(update.bookId()).orElse(null);
        KoboProgressState canonical = canonicalDto == null ? null
                : KoboProgressTranslator.toKobo(canonicalDto,
                        port.observeProgressVersion(update.bookId(), progressRevision(canonicalDto)));
        if (!KoboProgressTranslator.shouldAccept(update, canonical)) {
            return canonical;
        }
        ReadingProgressDto mapped = KoboProgressTranslator.toMyHomeLib(update, canonicalDto);
        progress.save(mapped);
        long version = port.observeProgressVersion(update.bookId(), progressRevision(mapped));
        return KoboProgressTranslator.toKobo(mapped, version);
    }

    static Optional<KoboBookProjection> project(Book book) {
        return projectInternal(book, List.of());
    }


    private static Optional<KoboBookProjection> projectInternal(Book book, List<ConvertBookUseCase.CapabilityView> capabilities) {
        BookArtifact preferred = book.getPreferredArtifact().filter(BookArtifact::isAvailable).orElse(null);
        if (preferred != null && koboCompatible(preferred)) return Optional.of(directProjection(book, preferred));

        BookArtifact source = preferred != null && conversionTarget(preferred, capabilities) != null
                ? preferred
                : book.getArtifacts().stream().filter(BookArtifact::isAvailable)
                    .filter(a -> conversionTarget(a, capabilities) != null).findFirst().orElse(null);
        if (source != null) {
            String target = conversionTarget(source, capabilities);
            return Optional.of(virtualProjection(book, source, target));
        }

        BookArtifact direct = book.getArtifacts().stream().filter(BookArtifact::isAvailable)
                .filter(KoboSyncService::koboCompatible).findFirst().orElse(null);
        return direct == null ? Optional.empty() : Optional.of(directProjection(book, direct));
    }

    private static KoboBookProjection directProjection(Book book, BookArtifact artifact) {
        String artifactRevision = firstNonBlank(artifact.getContentFingerprint(), artifact.getSha256(),
                artifact.getId() + ":" + artifact.getFile().getFileSize());
        String format = artifact.getFormat();
        String name = artifact.getFile().hasArchiveEntry()
                ? artifact.getFile().getArchiveEntry() : artifact.getFile().getFileName();
        Instant modified = book.getUpdateDate() == null ? Instant.EPOCH : book.getUpdateDate().toInstant(ZoneOffset.UTC);
        String revision = revision(book, artifact.getId(), artifactRevision, format);
        return new KoboBookProjection(book.getId().asString(), revision, book.getTitle(),
                book.authorsText(), book.getSeries(), book.getSequenceNumber(), artifact.getId(), artifactRevision,
                format, name, modified);
    }

    private static KoboBookProjection virtualProjection(Book book, BookArtifact source, String target) {
        String sourceRevision = firstNonBlank(source.getContentFingerprint(), source.getSha256(),
                source.getId() + ":" + source.getFile().getFileSize());
        String artifactRevision = sha256("kobo-conversion\u001f" + source.getId() + "\u001f" + sourceRevision + "\u001f" + target);
        String sourceName = source.getFile().hasArchiveEntry() ? source.getFile().getArchiveEntry() : source.getFile().getFileName();
        String stem = sourceName == null ? book.getTitle() : sourceName.replaceFirst("(?i)\\.[^.]+$", "");
        String name = stem + ("kepub".equals(target) ? ".kepub" : ".epub");
        Instant modified = book.getUpdateDate() == null ? Instant.EPOCH : book.getUpdateDate().toInstant(ZoneOffset.UTC);
        String virtualId = "virtual:" + encodeVirtualComponent(source.getId()) + ":" + target;
        String revision = revision(book, virtualId, artifactRevision, target);
        return new KoboBookProjection(book.getId().asString(), revision, book.getTitle(), book.authorsText(),
                book.getSeries(), book.getSequenceNumber(), virtualId, artifactRevision, target, name, modified);
    }

    private static String conversionTarget(BookArtifact artifact, List<ConvertBookUseCase.CapabilityView> capabilities) {
        if (artifact == null || capabilities == null || capabilities.isEmpty()) return null;
        String source = BookConversionCapability.normalizeFormat(artifact.getFormat());
        for (String target : List.of("kepub", "epub")) {
            boolean supported = capabilities.stream().anyMatch(view -> view.available()
                    && view.capability().produces(target) && view.capability().supportsSource(source));
            if (supported) return target;
        }
        return null;
    }

    private List<ConvertBookUseCase.CapabilityView> conversionCapabilities() {
        if (conversions == null) return List.of();
        try {
            return conversions.capabilityMatrix();
        } catch (RuntimeException unavailable) {
            log.warn("Kobo conversion capabilities unavailable; only direct Kobo-compatible artifacts will be exposed", unavailable);
            return List.of();
        }
    }

    private static boolean koboCompatible(BookArtifact artifact) {
        String format = safe(artifact.getFormat()).toLowerCase(java.util.Locale.ROOT);
        String name = artifact.getFile() == null ? "" : safe(artifact.getFile().getFileName()).toLowerCase(java.util.Locale.ROOT);
        return format.equals("epub") || format.equals("kepub") || format.equals("kepub.epub")
                || name.endsWith(".epub") || name.endsWith(".kepub.epub");
    }

    private static String encodeVirtualComponent(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(safe(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String revision(Book book, String artifactId, String artifactRevision, String format) {
        String material = String.join("\u001f",
                book.getId().asString(), safe(book.getTitle()), book.authorsText(), safe(book.getSeries()),
                String.valueOf(book.getSequenceNumber()), safe(artifactId), artifactRevision, safe(format),
                book.getUpdateDate() == null ? "" : book.getUpdateDate().toString());
        return sha256(material);
    }

    private static String sha256(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String progressRevision(ReadingProgressDto value) {
        String material = String.join("\u001f",
                safe(value.getBookId()), safe(value.getAnchorId()), String.valueOf(value.getParagraphIndex()),
                safe(value.getParagraphId()), String.valueOf(value.getCharOffset()),
                String.format(java.util.Locale.ROOT, "%.8f", value.getPercent()), safe(value.getChapterId()),
                value.getUpdatedAt() == null ? "" : value.getUpdatedAt().toString(),
                String.valueOf(value.getReadingTimeSeconds()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "unknown";
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
