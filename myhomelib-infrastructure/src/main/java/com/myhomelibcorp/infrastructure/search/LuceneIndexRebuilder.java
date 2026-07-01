package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.application.port.out.IndexRebuilder;
import com.myhomelibcorp.application.query.BookQuery;
import com.myhomelibcorp.application.query.Pagination;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LuceneIndexRebuilder implements IndexRebuilder {

    private final LuceneSearchIndexer indexer;
    private final BookQueryRepository bookQueryRepository;
    private final GenreService genreService;

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

            for (Book book : books) {
                try {
                    String genresText = book.getGenres().stream()
                            .map(genre -> genreService.getGenreName(genre.getId().asString()))
                            .collect(Collectors.joining(", "));

                    SearchDocument doc = SearchDocument.builder()
                            .id(book.getId().asString())
                            .title(book.getTitle() != null ? book.getTitle() : "")
                            .authors(book.authorsText())
                            .series(book.getSeries() != null ? book.getSeries() : "")
                            .genres(genresText)
                            .keywords(book.getMetadata().getKeywords() != null ? book.getMetadata().getKeywords() : "")
                            .annotation(book.getMetadata().getAnnotation() != null ? book.getMetadata().getAnnotation() : "")
                            .build();

                    indexer.indexDocument(doc);
                    totalIndexed++;
                } catch (Exception e) {
                    log.error("Помилка індексації книги id={}", book.getId().asString(), e);
                }
            }

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