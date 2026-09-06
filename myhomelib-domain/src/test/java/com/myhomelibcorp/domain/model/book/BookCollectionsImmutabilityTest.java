package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookCollectionsImmutabilityTest {
    @Test
    void authorsAndGenresCannotBeMutatedThroughGetters() {
        Book book = book(new ArrayList<>(List.of(new Author("Ada", "", "Lovelace"))),
                new ArrayList<>(List.of(new Genre("sf", "Science"))));
        assertThatThrownBy(() -> book.getAuthors().add(new Author("Grace", "", "Hopper")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> book.getGenres().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builderInputListsAreDefensivelyCopiedWhileDomainMethodsStillPopulateAggregate() {
        List<Author> authors = new ArrayList<>();
        List<Genre> genres = new ArrayList<>();
        Book book = book(authors, genres);
        authors.add(new Author("External", "", "Mutation"));
        genres.add(new Genre("external", "Mutation"));
        assertThat(book.getAuthors()).isEmpty();
        assertThat(book.getGenres()).isEmpty();
        book.addAuthor(new Author("Domain", "", "Author"));
        book.addGenre(new Genre("domain", "Genre"));
        assertThat(book.getAuthors()).hasSize(1);
        assertThat(book.getGenres()).hasSize(1);
    }

    private static Book book(List<Author> authors, List<Genre> genres) {
        return Book.builder().id(BookId.generate()).title("Immutable").authors(authors).genres(genres)
                .metadata(BookMetadata.empty()).file(BookFile.empty()).build();
    }
}
