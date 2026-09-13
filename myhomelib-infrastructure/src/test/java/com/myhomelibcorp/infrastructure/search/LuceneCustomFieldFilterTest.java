package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.search.CustomFieldSearchFilter;
import com.myhomelibcorp.application.query.search.CustomFieldSearchOperator;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.customfield.CustomFieldType;
import com.myhomelibcorp.domain.model.customfield.CustomFieldValue;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LuceneCustomFieldFilterTest {
    private LuceneSearchService search;

    @BeforeEach
    void setUp() {
        var analyzer = new StandardAnalyzer();
        search = new LuceneSearchService(new ByteBuffersDirectory(), analyzer,
                new QueryParser("title", analyzer), mock(BookQueryRepository.class));
        search.init();
        search.setQueryAvailability(true, "");
        search.indexSnapshot(snapshot("11111111-1111-1111-1111-111111111111", "One",
                List.of(new CustomFieldValue(1, CustomFieldType.NUMBER, "12.5"),
                        new CustomFieldValue(2, CustomFieldType.ENUM, "Ready"))));
        search.indexSnapshot(snapshot("22222222-2222-2222-2222-222222222222", "Two",
                List.of(new CustomFieldValue(1, CustomFieldType.NUMBER, "5"),
                        new CustomFieldValue(2, CustomFieldType.ENUM, "Draft"))));
        search.commit();
    }

    @AfterEach void tearDown() { if (search != null) search.close(); }

    @Test
    void numericAndEnumCustomFieldsAreFilterableThroughLucenePath() {
        var request = SearchRequest.builder().customFieldFilters(List.of(
                new CustomFieldSearchFilter(1, CustomFieldType.NUMBER, CustomFieldSearchOperator.AT_LEAST, "10", ""),
                new CustomFieldSearchFilter(2, CustomFieldType.ENUM, CustomFieldSearchOperator.EQUALS, "Ready", "")
        )).build();
        assertThat(search.search(request).bookIds())
                .containsExactly(BookId.fromString("11111111-1111-1111-1111-111111111111"));
    }

    private static BookSnapshot snapshot(String id, String title, List<CustomFieldValue> fields) {
        return BookSnapshot.builder().id(BookId.fromString(id)).title(title).authorsText("").authorIds("")
                .series("").genresText("").genreIds("").keywords("").annotation("").fileName("book.fb2")
                .language("uk").rate(0).progress(0).publisher("").libId(id).libraryRate(0).translators("")
                .city("").sourceUrl("").isbn("").deleted(false).local(true).customFieldValues(fields).build();
    }
}
