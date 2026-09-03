package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.filter.BookFilterMode;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookQuickFilterField;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.book.BookFormat;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LuceneUnifiedFilterTest {
    private LuceneSearchService search;
    private BookId alpha;
    private BookId beta;

    @BeforeEach
    void setUp() {
        var analyzer = new StandardAnalyzer();
        var parser = new MultiFieldQueryParser(
                new String[]{"title", "authors", "series", "genres", "keywords", "annotation", "file_name", "publisher"},
                analyzer);
        parser.setAllowLeadingWildcard(true);
        search = new LuceneSearchService(new ByteBuffersDirectory(), analyzer, parser, mock(BookQueryRepository.class));
        search.init();

        alpha = BookId.generate();
        beta = BookId.generate();
        search.indexSnapshot(snapshot(alpha, "Alpha History", "Writer One", "uk", "alpha.fb2", 2022, 5, 100, true));
        search.indexSnapshot(snapshot(beta, "Beta Space", "Writer Two", "en", "beta.epub", 2025, 2, 10, false));
        search.commit();
        // Standalone service tests bypass LuceneCollectionIndexLifecycle, which normally
        // publishes query availability after the collection index is validated/rebuilt.
        search.setQueryAvailability(true, null);
    }

    @AfterEach
    void tearDown() {
        if (search != null) search.close();
    }

    @Test
    void blankTextCanBeConstrainedBySameStructuredFilter() {
        BookFilterSpec filter = new BookFilterSpec(
                BookFilterMode.AND, "uk", 2020, 2024, BookFormat.FB2,
                true, true, 4, 5, false, BookQuickFilterField.ANY, null);

        assertThat(ids(search.search(SearchRequest.builder().text("").filterSpec(filter).build())))
                .containsExactly(alpha);
    }

    @Test
    void orModeMatchesAnySelectedCriterion() {
        BookFilterSpec filter = new BookFilterSpec(
                BookFilterMode.OR, "uk", null, null, BookFormat.EPUB,
                null, null, null, null, false, BookQuickFilterField.ANY, null);

        assertThat(ids(search.search(SearchRequest.builder().text("").filterSpec(filter).build())))
                .containsExactlyInAnyOrder(alpha, beta);
    }

    @Test
    void quickFilterSupportsSafeContainsLikeSubstringMatching() {
        BookFilterSpec filter = BookFilterSpec.empty().withQuickFilter(BookQuickFilterField.TITLE, "pha");
        assertThat(ids(search.search(SearchRequest.builder().text("").filterSpec(filter).build())))
                .containsExactly(alpha);
    }

    private Set<BookId> ids(com.myhomelibcorp.application.query.search.SearchResult result) {
        return result.bookIds().stream().collect(Collectors.toSet());
    }

    private BookSnapshot snapshot(BookId id, String title, String authors, String language, String file,
                                  int year, int rate, int progress, boolean local) {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);
        return BookSnapshot.builder()
                .id(id).title(title).authorsText(authors).authorIds("")
                .series("").genresText("").genreIds("").keywords("").annotation("")
                .fileName(file).publisher("").translators("").city("")
                .language(language).rate(rate).progress(progress).year(year).libraryRate(rate)
                .libId(id.asString()).createdAt(now).updateDate(now).local(local).deleted(false)
                .build();
    }
}
