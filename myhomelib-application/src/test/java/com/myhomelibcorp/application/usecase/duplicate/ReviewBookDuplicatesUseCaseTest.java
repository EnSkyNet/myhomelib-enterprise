package com.myhomelibcorp.application.usecase.duplicate;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateDetector;
import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateReason;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.duplicate.FuzzyDuplicateCandidateLookup;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReviewBookDuplicatesUseCaseTest {

    @Test
    void returnsBoundedExplainableUserFacingSuggestionsWithoutAutoMerge() {
        Book source = book("10000000-0000-0000-0000-000000000001", "Clean Code", "Robert", "Martin", 2008);
        Book positive = book("10000000-0000-0000-0000-000000000002", "Clean Cod", "Robert", "Martin", 2008);
        Book negative = book("10000000-0000-0000-0000-000000000003", "Common Title", "Bob", "Writer", 2020);

        BookQueryRepository books = mock(BookQueryRepository.class);
        FuzzyDuplicateCandidateLookup lookup = mock(FuzzyDuplicateCandidateLookup.class);
        BookMapper mapper = mock(BookMapper.class);
        when(books.findById(source.getId())).thenReturn(Optional.of(source));
        when(lookup.findCandidateIds(source, ReviewBookDuplicatesUseCase.MAX_LIMIT))
                .thenReturn(List.of(positive.getId(), negative.getId(), positive.getId()));
        when(books.findByIds(List.of(positive.getId(), negative.getId())))
                .thenReturn(List.of(negative, positive));
        when(mapper.toDto(source)).thenReturn(BookDto.builder().id(source.getId().asString()).title(source.getTitle()).build());
        when(mapper.toDto(positive)).thenReturn(BookDto.builder().id(positive.getId().asString()).title(positive.getTitle()).build());

        ReviewBookDuplicatesUseCase useCase = new ReviewBookDuplicatesUseCase(
                books, lookup, new FuzzyDuplicateDetector(), mapper);

        var suggestions = useCase.review(source.getId(), 9999);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.getFirst().source().getTitle()).isEqualTo("Clean Code");
        assertThat(suggestions.getFirst().candidate().getTitle()).isEqualTo("Clean Cod");
        assertThat(suggestions.getFirst().score()).isBetween(FuzzyDuplicateDetector.DEFAULT_THRESHOLD, 1.0);
        assertThat(suggestions.getFirst().reasons())
                .contains(FuzzyDuplicateReason.TITLE_SIMILAR, FuzzyDuplicateReason.AUTHOR_EXACT, FuzzyDuplicateReason.YEAR_MATCH);
        verify(lookup).findCandidateIds(source, ReviewBookDuplicatesUseCase.MAX_LIMIT);
        verifyNoMoreInteractions(lookup);
    }

    private static Book book(String id, String title, String firstName, String lastName, Integer year) {
        return Book.builder()
                .id(BookId.fromString(id))
                .title(title)
                .authors(List.of(new Author(firstName, "", lastName)))
                .metadata(BookMetadata.builder().year(year).build())
                .file(BookFile.empty())
                .build();
    }
}
