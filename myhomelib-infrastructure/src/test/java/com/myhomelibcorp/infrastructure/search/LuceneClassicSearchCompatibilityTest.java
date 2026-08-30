package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
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

class LuceneClassicSearchCompatibilityTest {
    private LuceneSearchService search;
    private BookId first;
    private BookId second;

    @BeforeEach
    void setUp() {
        var analyzer = new StandardAnalyzer();
        var parser = new MultiFieldQueryParser(
                new String[]{"title", "authors", "series", "genres", "keywords", "annotation", "file_name", "publisher"},
                analyzer);
        parser.setAllowLeadingWildcard(true);
        search = new LuceneSearchService(new ByteBuffersDirectory(), analyzer, parser, mock(BookQueryRepository.class));
        search.init();

        first = BookId.generate();
        second = BookId.generate();
        search.indexSnapshot(snapshot(first, "Київська історія", "Іван Франко", "uk", 2020, 5, 4,
                LocalDateTime.of(2024, 1, 15, 12, 0)));
        search.indexSnapshot(snapshot(second, "Львівські оповідання", "Леся Українка", "en", 2023, 3, 2,
                LocalDateTime.of(2025, 3, 10, 12, 0)));
        search.commit();
    }

    @AfterEach
    void tearDown() {
        if (search != null) search.close();
    }

    @Test
    void supportsPercentContainsExactOrAndAliases() {
        assertIds("%істор%", first);
        assertIds("=\"Київська історія\"", first);
        assertIds("author:Франко", first);
        assertIds("автор:Українка", second);
        assertIds("назва:Київська OR назва:Львівські", first, second);
        assertIds("lang:uk", first);
    }

    @Test
    void supportsClassicNumericAndDateComparisonsIncludingNotEqual() {
        assertIds("year>=2023", second);
        assertIds("year<2023", first);
        assertIds("rate>3", first);
        assertIds("library_rate<=2", second);
        assertIds("year<>2020", second);
        assertIds("added>=2025-01-01", second);
    }

    @Test
    void combinesClassicSyntaxWithStructuredFilters() {
        var result = search.search(SearchRequest.builder()
                .text("%оповідан%")
                .language(com.myhomelibcorp.domain.model.valueobject.LanguageCode.of("en"))
                .ratingFrom(2)
                .yearFrom(2023)
                .build());
        assertThat(ids(result)).containsExactly(second);
    }

    @Test
    void reportsExactTotalHitsAboveLuceneDefaultTrackingThreshold() {
        int count = 1_500;
        for (int i = 0; i < count; i++) {
            BookId id = BookId.generate();
            search.indexSnapshot(snapshot(id, "bulkmarker item " + i, "Bulk Author", "uk", 2026, 1, 1,
                    LocalDateTime.of(2026, 1, 1, 12, 0)));
        }
        search.commit();

        var result = search.search(SearchRequest.builder()
                .text("bulkmarker")
                .limit(25)
                .offset(1_200)
                .build());

        assertThat(result.totalHits()).isEqualTo(count);
        assertThat(result.bookIds()).hasSize(25);
        assertThat(result.page()).isEqualTo(48);
    }

    private void assertIds(String query, BookId... expected) {
        var result = search.search(SearchRequest.builder().text(query).build());
        assertThat(ids(result)).containsExactlyInAnyOrder(expected);
    }

    private Set<BookId> ids(com.myhomelibcorp.application.query.search.SearchResult result) {
        return result.bookIds().stream().collect(Collectors.toSet());
    }

    private BookSnapshot snapshot(BookId id, String title, String authors, String language,
                                  int year, int rate, int libraryRate, LocalDateTime created) {
        return BookSnapshot.builder()
                .id(id).title(title).authorsText(authors).authorIds("")
                .series("").genresText("").genreIds("").keywords("").annotation("")
                .fileName(title + ".fb2").publisher("").translators("").city("")
                .language(language).rate(rate).progress(0).year(year).libraryRate(libraryRate)
                .libId(id.asString()).createdAt(created).updateDate(created).local(true).deleted(false)
                .build();
    }
}
