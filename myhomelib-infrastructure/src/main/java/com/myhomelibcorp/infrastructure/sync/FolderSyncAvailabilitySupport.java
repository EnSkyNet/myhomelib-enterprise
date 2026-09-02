package com.myhomelibcorp.infrastructure.sync;

import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Local-file availability lifecycle used by folder sync.
 * Missing physical files are marked as unavailable without deleting catalogue/user state.
 */
@Slf4j
final class FolderSyncAvailabilitySupport {

    Result markMissingPhysicalFiles(Path root,
                                    AtomicBoolean cancelFlag,
                                    BookQueryRepository queries,
                                    BookCommandRepository commands,
                                    SearchIndexer indexer,
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
                    mark(book, false, commands, indexer);
                    unavailable++;
                } catch (Exception e) {
                    errors++;
                    log.error("Не вдалося позначити відсутній локальний файл для {}", book.getId(), e);
                }
            }
        }
        return new Result(unavailable, errors, unavailable > 0);
    }

    int restore(List<Book> books, BookCommandRepository commands, SearchIndexer indexer) {
        int restored = 0;
        for (Book book : books) {
            if (book != null && !book.isLocal()) {
                mark(book, true, commands, indexer);
                restored++;
            }
        }
        return restored;
    }

    boolean restore(Book book, BookCommandRepository commands, SearchIndexer indexer) {
        if (book == null || book.isLocal()) return false;
        mark(book, true, commands, indexer);
        return true;
    }

    void mark(Book book, boolean local, BookCommandRepository commands, SearchIndexer indexer) {
        if (local) {
            commands.updateStorage(book.getId(), book.getCollectionRoot(), book.getFolder(),
                    book.getFileName(), book.getArchiveEntry(), true);
            indexer.indexBook(book.withLocalAvailability(true, null));
        } else {
            commands.markStorageMissing(book.getId());
            indexer.indexBook(book.withLocalAvailability(false, LocalDateTime.now()));
        }
    }

    record Result(int updated, int errors, boolean indexDirty) {}
}
