package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class LuceneIndexRebuilder implements IndexRebuilder {

    private final LuceneSearchIndexer indexer;
    private final BookQueryRepository bookQueryRepository;
    private final GenreRepository genreRepository;

    @Override
    public void rebuildIndex() {
        log.info("Початок перебудови Lucene індексу...");
        indexer.rebuildIndex();

        int pageSize = 1000;
        int offset = 0;
        int totalIndexed = 0;

        while (true) {
            BookQuery query = BookQuery.builder()
                    .pagination(Pagination.of(pageSize, offset))
                    .build();
            List<Book> books = bookQueryRepository.find(query);
            if (books.isEmpty()) {
                break;
            }

            indexer.indexAll(books);
            totalIndexed += books.size();
            log.debug("Проіндексовано {} книг (всього {})", books.size(), totalIndexed);
            offset += pageSize;
        }

        log.info("Перебудова індексу завершена. Проіндексовано {} книг", totalIndexed);
    }

    @Override
    public int getIndexedDocumentCount() {
        return indexer.getDocumentCount();
    }
}