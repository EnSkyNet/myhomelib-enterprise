package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionScopedCachesTest {

    @Test
    void sameLocalKeysCannotLeakAcrossCollections() {
        CollectionManager manager = mock(CollectionManager.class);
        Collection first = collection("collection-a");
        Collection second = collection("collection-b");
        AtomicReference<Collection> active = new AtomicReference<>(first);
        when(manager.getCurrentCollection()).thenAnswer(ignored -> active.get());

        CaffeineAuthorCache authors = new CaffeineAuthorCache(manager);
        CaffeineGenreCache genres = new CaffeineGenreCache(manager);
        CaffeineSeriesCache series = new CaffeineSeriesCache(manager);
        CaffeineSearchCache searches = new CaffeineSearchCache(manager);
        CaffeineCoverCache covers = new CaffeineCoverCache(manager);
        BookCache books = new BookCache(manager);

        AuthorId authorId = AuthorId.fromLong(42L);
        GenreId genreId = GenreId.fromCode("sf");
        SeriesId seriesId = SeriesId.fromString("00000000-0000-0000-0000-000000000042");
        BookId bookId = BookId.fromString("00000000-0000-0000-0000-000000000099");
        Author authorA = mock(Author.class);
        Genre genreA = mock(Genre.class);
        Series seriesA = mock(Series.class);
        byte[] coverA = {1, 2, 3};
        Book bookA = mock(Book.class);

        authors.put(authorId, authorA);
        genres.put(genreId, genreA);
        series.put(seriesId, seriesA);
        searches.put("same-query", List.of(bookId));
        covers.put("same-book-id", coverA);
        books.put(bookId, bookA);

        active.set(second);

        assertThat(authors.get(authorId)).isEmpty();
        assertThat(genres.get(genreId)).isEmpty();
        assertThat(series.get(seriesId)).isEmpty();
        assertThat(searches.get("same-query")).isNull();
        assertThat(covers.get("same-book-id")).isNull();
        assertThat(books.get(bookId)).isEmpty();

        active.set(first);

        assertThat(authors.get(authorId)).containsSame(authorA);
        assertThat(genres.get(genreId)).containsSame(genreA);
        assertThat(series.get(seriesId)).containsSame(seriesA);
        assertThat(searches.get("same-query")).containsExactly(bookId);
        assertThat(covers.get("same-book-id")).containsExactly(coverA);
        assertThat(books.get(bookId)).containsSame(bookA);
    }

    @Test
    void asynchronousDeleteEvictionRemovesBookFromEveryCollectionScope() {
        CollectionManager manager = mock(CollectionManager.class);
        Collection first = collection("collection-a");
        Collection second = collection("collection-b");
        AtomicReference<Collection> active = new AtomicReference<>(first);
        when(manager.getCurrentCollection()).thenAnswer(ignored -> active.get());

        BookCache books = new BookCache(manager);
        BookId bookId = BookId.fromString("00000000-0000-0000-0000-000000000099");
        Book firstBook = mock(Book.class);
        Book secondBook = mock(Book.class);

        books.put(bookId, firstBook);
        active.set(second);
        books.put(bookId, secondBook);

        books.evictAllScopes(bookId);

        assertThat(books.get(bookId)).isEmpty();
        active.set(first);
        assertThat(books.get(bookId)).isEmpty();
    }

    private static Collection collection(String id) {
        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn(id);
        return collection;
    }
}
