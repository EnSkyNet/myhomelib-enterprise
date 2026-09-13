package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.search.*;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LuceneSmartCollectionTest {
    private LuceneSearchService search;
    private BookId alpha;
    private BookId beta;
    private BookId gamma;
    private BookId delta;

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
        gamma = BookId.generate();
        delta = BookId.generate();
        search.indexSnapshot(snapshot(alpha, "Alpha History", "Writer One", "uk", "alpha.fb2", 2022, 5, 100, true, 1));
        search.indexSnapshot(snapshot(beta, "Beta Space", "Writer Two", "en", "beta.epub", 2025, 2, 10, false, 2));
        search.indexSnapshot(snapshot(gamma, "Gamma Space Opera", "Writer Three", "uk", "gamma.epub", 2024, 4, 50, true, 3));
        search.indexSnapshot(snapshot(delta, "Delta Science", "Writer Four", "uk", "delta.fb2", 2023, 3, 20, true, 4));
        search.commit();
        search.setQueryAvailability(true, null);
    }

    @AfterEach
    void tearDown() {
        if (search != null) search.close();
    }

    @Test
    void andModeCombinesTypedMetadataAndNumericRules() {
        SmartCollectionSpec spec = spec(SmartCollectionMode.AND, List.of(
                SmartCollectionRule.text(SmartCollectionField.LANGUAGE, SmartCollectionOperator.EQUALS, "uk"),
                SmartCollectionRule.number(SmartCollectionField.RATING, SmartCollectionOperator.AT_LEAST, 4, null)
        ), SmartCollectionSort.TITLE, SmartCollectionSortDirection.ASC, 100);

        assertThat(search(spec).bookIds()).containsExactly(alpha, gamma);
    }

    @Test
    void orModeSupportsFormatProgressAndContainsRules() {
        SmartCollectionSpec spec = spec(SmartCollectionMode.OR, List.of(
                SmartCollectionRule.text(SmartCollectionField.FORMAT, SmartCollectionOperator.EQUALS, "epub"),
                SmartCollectionRule.number(SmartCollectionField.PROGRESS, SmartCollectionOperator.EQUALS, 100, null),
                SmartCollectionRule.text(SmartCollectionField.TITLE, SmartCollectionOperator.CONTAINS, "science")
        ), SmartCollectionSort.TITLE, SmartCollectionSortDirection.ASC, 100);

        assertThat(search(spec).bookIds()).containsExactly(alpha, beta, delta, gamma);
    }

    @Test
    void betweenSortAndMaxResultsAreAppliedInsideLucene() {
        SmartCollectionSpec spec = spec(SmartCollectionMode.AND, List.of(
                SmartCollectionRule.text(SmartCollectionField.LANGUAGE, SmartCollectionOperator.EQUALS, "uk"),
                SmartCollectionRule.number(SmartCollectionField.YEAR, SmartCollectionOperator.BETWEEN, 2022, 2024)
        ), SmartCollectionSort.YEAR, SmartCollectionSortDirection.DESC, 2);

        var result = search(spec);
        assertThat(result.bookIds()).containsExactly(gamma, delta);
        assertThat(result.totalHits()).isEqualTo(2);
    }

    @Test
    void notEqualsExcludesMatchingValueWithoutLosingOtherDocuments() {
        SmartCollectionSpec spec = spec(SmartCollectionMode.AND, List.of(
                SmartCollectionRule.text(SmartCollectionField.LANGUAGE, SmartCollectionOperator.NOT_EQUALS, "en")
        ), SmartCollectionSort.YEAR, SmartCollectionSortDirection.ASC, 100);

        assertThat(search(spec).bookIds()).containsExactly(alpha, delta, gamma);
    }

    private com.myhomelibcorp.application.query.search.SearchResult search(SmartCollectionSpec spec) {
        return search.search(SearchRequest.builder().text("").smartCollectionSpec(spec).limit(100).build());
    }

    private static SmartCollectionSpec spec(SmartCollectionMode mode, List<SmartCollectionRule> rules,
                                            SmartCollectionSort sort, SmartCollectionSortDirection direction,
                                            int maxResults) {
        return new SmartCollectionSpec(mode, rules, sort, direction, maxResults);
    }

    private BookSnapshot snapshot(BookId id, String title, String authors, String language, String file,
                                  int year, int rate, int progress, boolean local, int day) {
        LocalDateTime created = LocalDateTime.of(2026, 1, day, 12, 0);
        return BookSnapshot.builder()
                .id(id).title(title).authorsText(authors).authorIds("")
                .series("").genresText("").genreIds("").keywords("").annotation("")
                .fileName(file).publisher("").translators("").city("")
                .language(language).rate(rate).progress(progress).year(year).libraryRate(rate)
                .libId(id.asString()).createdAt(created).updateDate(created).local(local).deleted(false)
                .build();
    }
}
