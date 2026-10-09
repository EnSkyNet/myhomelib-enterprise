package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.book.ExternalReaderMaterializationCache;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.shared.util.ExecutablePathSafety;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class ExternalBookLauncher {
    private final ApplicationSettingsPort settings;
    private final BookResourcePort resources;
    private final ExternalReaderMaterializationCache materializationCache;
    private final LibraryOperationCoordinator operationCoordinator;
    private final CollectionLifecyclePort collectionLifecycle;

    /** Compatibility constructor for focused tests/non-Spring embedding. */
    public ExternalBookLauncher(ApplicationSettingsPort settings,
                                BookResourcePort resources,
                                ExternalReaderMaterializationCache materializationCache) {
        this(settings, resources, materializationCache, null, null);
    }

    @Autowired
    public ExternalBookLauncher(ApplicationSettingsPort settings,
                                BookResourcePort resources,
                                ExternalReaderMaterializationCache materializationCache,
                                LibraryOperationCoordinator operationCoordinator,
                                CollectionLifecyclePort collectionLifecycle) {
        this.settings = settings;
        this.resources = resources;
        this.materializationCache = materializationCache;
        this.operationCoordinator = operationCoordinator;
        this.collectionLifecycle = collectionLifecycle;
    }

    public void open(BookDto dto) throws Exception {
        open(dto, null);
    }

    /**
     * Opens the resource while holding a stable-reader lifecycle barrier. When an expected
     * collection is supplied by an earlier async step, a stale completion fails closed instead
     * of materializing/launching a book from the previous collection.
     */
    public void open(BookDto dto, String expectedCollectionId) throws Exception {
        if (operationCoordinator == null || collectionLifecycle == null) {
            openLocked(dto);
            return;
        }
        LibraryOperationCoordinator.Lease lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.READER_STATE);
        try {
            if (expectedCollectionId != null && !expectedCollectionId.isBlank()) {
                var active = collectionLifecycle.getCurrentCollection();
                if (active == null || active.getId() == null || !expectedCollectionId.equals(active.getId())) {
                    throw new IllegalStateException(UiText.text("ui.error.collection_changed.book_open"));
                }
            }
            openLocked(dto);
            lease.markCompleted("");
        } catch (Exception | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private void openLocked(BookDto dto) throws Exception {
        if (dto == null) throw new IllegalArgumentException(UiText.text("ui.java.external_book_launcher.1215889a21"));
        String source = nonBlank(dto.getArchiveEntry(), dto.getFileName());
        String ext = extension(source);
        MaterializedExternalBook materialized = materialize(dto, ext);
        ExternalReaderMaterializationCache.Lease lease = materialized.lease();
        Path file = materialized.path();
        try {
            String configured = settings.get("reader.external." + ext, "");
            String command = configured == null ? "" : configured.trim();
            if (command.isEmpty()) {
                if (!Desktop.isDesktopSupported()) throw new IllegalStateException(UiText.text("ui.java.external_book_launcher.915aa31d98"));
                Desktop.getDesktop().open(file.toFile());
                if (lease != null) lease.keepUntilNextStartup();
                return;
            }
            List<String> args = new java.util.ArrayList<>(CommandTemplate.expand(command, Map.of(
                    "%FILE%", file.toAbsolutePath().toString(),
                    "%TITLE%", safe(dto.getTitle()),
                    "%AUTHOR%", safe(dto.getAuthorsText()))));
            if (args.isEmpty() || args.getFirst().isBlank()) {
                throw new IllegalArgumentException(UiText.text("ui.error.external_reader.executable_missing"));
            }
            Path safeExecutable = ExecutablePathSafety.resolve(args.getFirst(), "External reader executable");
            args.set(0, safeExecutable.toString());
            if (args.stream().noneMatch(a -> a.equals(file.toAbsolutePath().toString()))) {
                args.add(file.toAbsolutePath().toString());
            }
            // The launched reader is detached from MyHomeLib. Do not leave its stdout/stderr as
            // PIPEs: a verbose external application could otherwise fill an unread pipe and
            // block even though MyHomeLib never consumes the output.
            Process process = new ProcessBuilder(args)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (lease != null) lease.retainUntil(process);
        } finally {
            if (lease != null) lease.close();
        }
    }

    private MaterializedExternalBook materialize(BookDto dto, String ext) throws Exception {
        if (dto.getArchiveEntry() == null || dto.getArchiveEntry().isBlank()) {
            Path located = resources.locateBookFile(
                    dto.getFileName(), dto.getFolder(), dto.getCollectionRoot(), dto.getArchiveEntry()).orElse(null);
            if (located != null && Files.isRegularFile(located, LinkOption.NOFOLLOW_LINKS) && !resources.isArchive(located.toString())) {
                return new MaterializedExternalBook(located.toAbsolutePath().normalize(), null);
            }
        }
        try (InputStream in = resources.readBookData(
                        dto.getFileName(), dto.getFolder(), dto.getCollectionRoot(), dto.getArchiveEntry())
                .orElseThrow(() -> new IllegalStateException(UiText.text("ui.java.external_book_launcher.b323823f4e")))) {
            ExternalReaderMaterializationCache.Lease lease = materializationCache.materialize(in, ext);
            return new MaterializedExternalBook(lease.path(), lease);
        }
    }

    private static String extension(String s) {
        int i = s == null ? -1 : s.lastIndexOf('.');
        return i < 0 ? "" : s.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(String a, String b) { return a != null && !a.isBlank() ? a : (b == null ? "" : b); }
    private static String safe(String s) { return s == null ? "" : s; }

    private record MaterializedExternalBook(Path path, ExternalReaderMaterializationCache.Lease lease) { }
}
