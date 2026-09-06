package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.application.port.out.cache.GenreCache;
import com.myhomelibcorp.application.port.out.cache.SeriesCache;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.cache.CaffeineCoverCache;
import com.myhomelibcorp.infrastructure.cache.CaffeineSearchCache;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CacheInvalidationAdapterTest {
    @Test
    void invalidateAllClearsCollectionScopedReferenceCachesToo() {
        BookCache books = mock(BookCache.class);
        CaffeineSearchCache search = mock(CaffeineSearchCache.class);
        CaffeineCoverCache covers = mock(CaffeineCoverCache.class);
        AuthorCache authors = mock(AuthorCache.class);
        GenreCache genres = mock(GenreCache.class);
        SeriesCache series = mock(SeriesCache.class);
        CacheInvalidationAdapter adapter = new CacheInvalidationAdapter(
                books, search, covers, authors, genres, series);

        adapter.invalidateAll();

        verify(books).clear();
        verify(search).clear();
        verify(covers).clear();
        verify(authors).clear();
        verify(genres).clear();
        verify(series).clear();
    }
}
