package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
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
        try (Stream<Book> books = queries.streamAll()) {
            var iterator = books.iterator();
            while (iterator.hasNext()) {
                if (cancelFlag.get()) break;
                Book book = iterator.next();
                if (book == null || !book.isLocal()) continue;
                Path physical = syncSupport.physicalPath(book, root);
                if (physical == null || !physical.startsWith(root) || Files.isRegularFile(physical)) continue;
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
