package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeriesOmnibusPlannerTest {
    private final BookQueryRepository repo = mock(BookQueryRepository.class);
    private final SeriesOmnibusPlanner planner = new SeriesOmnibusPlanner(repo);

    @Test
    void oneSelectedBookExpandsFullSeriesInSequenceWithoutOtherAuthorsOrCycles() {
        Author alice = new Author(AuthorId.generate(), "Alice", "", "Author");
        Author bob = new Author(AuthorId.generate(), "Bob", "", "Other");
        Book first = book(alice, "Saga", "Book one", 1);
        Book second = book(alice, "saga", "Book two", 2);
        Book third = book(alice, "SAGA", "Book three", 3);
        Book otherAuthor = book(bob, "Saga", "Unrelated", 0);
        Book otherSeries = book(alice, "Different", "Unrelated", 4);
        when(repo.findById(second.getId())).thenReturn(Optional.of(second));
        when(repo.streamAll()).thenAnswer(x ->
                Stream.of(third, second, otherAuthor, first, otherSeries));

        SeriesOmnibusPlanner.SeriesPlan plan = planner.plan(List.of(second.getId()));

        assertThat(plan.author()).isEqualTo(alice.getFullName());
        assertThat(plan.series()).isEqualTo("saga");
        assertThat(plan.bookIds()).containsExactly(first.getId(), second.getId(), third.getId());
    }

    @Test
    void differentAuthorOrSeriesInSelectionIsRejected() {
        Author alice = new Author(AuthorId.generate(), "Alice", "", "Author");
        Author bob = new Author(AuthorId.generate(), "Bob", "", "Other");
        Book first = book(alice, "Saga", "One", 1);
        Book different = book(bob, "Saga", "Two", 2);
        when(repo.findById(first.getId())).thenReturn(Optional.of(first));
        when(repo.findById(different.getId())).thenReturn(Optional.of(different));

        assertThatThrownBy(() -> planner.plan(List.of(first.getId(), different.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same author's series");
    }

    @Test
    void seriesWithoutAuthorOrSeriesNameIsRejected() {
        Book invalid = mock(Book.class);
        BookId id = BookId.generate();
        when(invalid.getId()).thenReturn(id);
        when(invalid.getTitle()).thenReturn("Unrelated");
        when(invalid.getAuthors()).thenReturn(List.of());
        when(invalid.getSeries()).thenReturn(null);
        when(repo.findById(id)).thenReturn(Optional.of(invalid));

        assertThatThrownBy(() -> planner.plan(List.of(id)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Book book(Author author, String series, String title, int sequence) {
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(BookId.generate());
        when(book.getTitle()).thenReturn(title);
        when(book.getSeries()).thenReturn(series);
        when(book.getSequenceNumber()).thenReturn(sequence);
        when(book.getAuthors()).thenReturn(List.of(author));
        return book;
    }
}
