package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.search.SearchIndexUnavailableException;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class LuceneSearchAvailabilityTest {

    @Test
    void searchIsRejectedUntilLifecycleProvesIndexReady() {
        var analyzer = new StandardAnalyzer();
        var search = new LuceneSearchService(new ByteBuffersDirectory(), analyzer,
                new QueryParser("all", analyzer), mock(BookQueryRepository.class));
        search.init();
        try {
            assertThrows(SearchIndexUnavailableException.class,
                    () -> search.search(SearchRequest.builder().text("book").limit(10).build()));
            search.setQueryAvailability(true, null);
            assertDoesNotThrow(() -> search.search(SearchRequest.builder().text("book").limit(10).build()));
            search.setQueryAvailability(false, "rebuilding");
            assertThrows(SearchIndexUnavailableException.class,
                    () -> search.search(SearchRequest.builder().text("book").limit(10).build()));
        } finally {
            search.close();
        }
    }
}
