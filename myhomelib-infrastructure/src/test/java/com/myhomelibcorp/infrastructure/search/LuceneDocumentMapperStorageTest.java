package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneDocumentMapperStorageTest {

    @Test
    void storesOnlyStableIdWhileKeepingSearchFieldsIndexed() {
        var snapshot = BookSnapshot.builder()
                .id(BookId.generate())
                .title("Title")
                .authorsText("Author")
                .authorIds("")
                .series("Series")
                .genresText("Genre")
                .genreIds("")
                .keywords("keyword")
                .annotation("large searchable annotation")
                .fileName("book.fb2")
                .publisher("Publisher")
                .translators("Translator")
                .city("City")
                .libId("lib-1")
                .isbn("9780000000000")
                .sourceUrl("https://example.invalid/book")
                .language("uk")
                .rate(5)
                .progress(0)
                .year(2026)
                .libraryRate(4)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .updateDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .local(true)
                .deleted(false)
                .build();

        var doc = new LuceneDocumentMapper().toDocument(snapshot);

        assertThat(doc.getFields().stream()
                .filter(field -> field.fieldType().stored())
                .map(field -> field.name())
                .distinct()
                .toList())
                .containsExactly("id");
        assertThat(doc.getField("annotation").fieldType().indexOptions())
                .isNotEqualTo(org.apache.lucene.index.IndexOptions.NONE);
    }
}
