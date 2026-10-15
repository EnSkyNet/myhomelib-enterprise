package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Local-file availability lifecycle used by folder sync. */
@Slf4j
final class FolderSyncAvailabilitySupport {

    Result markMissingPhysicalFiles(Path root,
                                    AtomicBoolean cancelFlag,
                                    BookQueryRepository queries,
                                    CommittedCatalogMutationService mutations,
                                    FolderSyncBookSupport syncSupport) {
        int unavailable = 0;
        int errors = 0;
        // A detached/unavailable collection root must never make the whole collection non-local.
        try {
            SafeFilePublication.requireSafeDirectoryPath(root, "folder-sync root");
        } catch (IOException | SecurityException unavailableRoot) {
            log.error("Cannot verify folder-sync root; availability flags unchanged: {}", root, unavailableRoot);
            return new Result(0, 1);
        }
        try (Stream<Book> books = queries.streamAll()) {
            var iterator = books.iterator();
            while (iterator.hasNext()) {
                if (cancelFlag.get()) break;
                Book book = iterator.next();
                if (book == null || !book.isLocal()) continue;
                Path physical = syncSupport.physicalPath(book, root);
                if (physical == null || !physical.startsWith(root)) {
                    errors++;
                    log.warn("Skipping availability change for unresolved/out-of-root book path: {}", book.getId());
                    continue;
                }
                Availability status = availabilityOf(physical, root);
                if (status == Availability.PRESENT) continue;
                if (status == Availability.UNKNOWN) {
                    errors++;
                    continue;
                }
                // Availability may change during enumeration (especially disconnected SMB mounts).
                // Verify the root again immediately before changing the catalogue flag.
                try {
                    SafeFilePublication.requireSafeDirectoryPath(root, "folder-sync root before reconciliation");
                } catch (IOException | SecurityException vanishedRoot) {
                    errors++;
                    log.error("Folder-sync root vanished during reconciliation: {}", root, vanishedRoot);
                    break;
                }
                // The file may have returned between enumeration and the root check.
                // A second check is cheap compared with changing a persistent local flag.
                Availability confirmed = availabilityOf(physical, root);
                if (confirmed == Availability.PRESENT) continue;
                if (confirmed == Availability.UNKNOWN) {
                    errors++;
                    continue;
                }
                try {
                    mark(book, false, mutations);
                    unavailable++;
                } catch (Exception e) {
                    errors++;
                    log.error("Не вдалося позначити відсутній локальний файл для {}", book.getId(), e);
                }
            }
        }
        return new Result(unavailable, errors);
    }

    enum Availability { PRESENT, MISSING, UNKNOWN }

    Availability availabilityOf(Path physical, Path root) {
        try {
            Path parent = physical.getParent();
            Path current = root;
            if (parent == null || !parent.startsWith(root)) return Availability.UNKNOWN;
            for (Path component : root.relativize(parent)) {
                current = current.resolve(component);
                try {
                    BasicFileAttributes attrs = Files.readAttributes(current,
                            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.isDirectory()) {
                        log.warn("Unsafe/non-directory folder-sync resource parent: {}", current);
                        return Availability.UNKNOWN;
                    }
                } catch (NoSuchFileException absent) {
                    return Availability.MISSING;
                }
            }
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(physical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                return Availability.MISSING;
            }
            if (!attrs.isRegularFile()) {
                log.warn("Unsafe/non-regular folder-sync book resource: {}", physical);
                return Availability.UNKNOWN;
            }
            return Availability.PRESENT;
        } catch (IOException | SecurityException uncertain) {
            log.warn("Could not verify book availability at {}: {}", physical, uncertain.getMessage());
            return Availability.UNKNOWN;
        }
    }

    int restore(List<Book> books, CommittedCatalogMutationService mutations) {
        int restored = 0;
        for (Book book : books) {
            if (book != null && !book.isLocal()) {
                mark(book, true, mutations);
                restored++;
            }
        }
        return restored;
    }

    boolean restore(Book book, CommittedCatalogMutationService mutations) {
        if (book == null || book.isLocal()) return false;
        mark(book, true, mutations);
        return true;
    }

    void mark(Book book, boolean local, CommittedCatalogMutationService mutations) {
        mutations.updateAvailability(book, local);
    }

    record Result(int updated, int errors) {}
}
