package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;

import java.io.IOException;
import java.util.Optional;

/**
 * Opens an isolated read-only snapshot of one registered collection database.
 *
 * <p>The portable group exporter uses one session at a time and resolves the same
 * normalized group name in each collection. This keeps collection identity explicit
 * without switching the application's active collection or mutating its database.</p>
 */
public interface CollectionBookReadPort {

    Session open(Collection collection) throws IOException;

    interface Session extends AutoCloseable {
        Optional<Group> findGroupById(Long groupId);

        /**
         * Resolves a collection-local group by its logical cross-collection name.
         * Implementations must reject ambiguous case/whitespace variants rather than
         * silently choosing one.
         */
        Optional<Group> findGroupByName(String name);

        /**
         * Returns complete book records for portable export, including bibliographic
         * metadata, hydrated authors/genres/artifacts, and deleted group members.
         */
        PageResult<Book> findPage(BookQuery query);

        /** Same full-record contract using the stable TITLE keyset cursor. */
        PageResult<Book> findTitlePageByCursor(BookQuery query,
                                               BookPageCursor cursor,
                                               BookPageDirection direction,
                                               long knownTotal);

        @Override
        void close() throws IOException;
    }
}
