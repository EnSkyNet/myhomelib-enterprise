package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.author.AuthorNameKey;
import com.myhomelibcorp.domain.model.genre.Genre;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InpxBookNormalizerTest {

    @Test
    void missingAuthorAndGenreRemainImportableAndExplicitDelIsPreserved(@TempDir Path root) {
        InpxBookNormalizer normalizer = new InpxBookNormalizer(new HashMap<>(), new HashMap<>());
        Map<AuthorNameKey, Author> pendingAuthors = new HashMap<>();
        Map<String, Genre> pendingGenres = new HashMap<>();
        InpxRecord record = new InpxRecord(Map.of(
                "TITLE", "Книга без метаданих",
                "FILE", "12345",
                "EXT", "fb2",
                "LIBID", "12345",
                "DEL", "1"
        ), "books.inp", "");

        InpxBookNormalizer.NormalizedBook result = normalizer.normalize(
                record, pendingAuthors, pendingGenres, root, new HashMap<>(), "test-source", false);

        assertThat(result).isNotNull();
        assertThat(result.withoutAuthor()).isTrue();
        assertThat(result.withoutGenre()).isTrue();
        assertThat(result.explicitlyDeleted()).isTrue();
        assertThat(pendingAuthors.keySet()).anyMatch(key -> InpxBookNormalizer.WITHOUT_AUTHOR_NAME.equals(key.lastName()));
        assertThat((java.util.List<?>) result.row()[20]).isEmpty();
        assertThat(result.row()[15]).isEqualTo(1);
    }

    @Test
    void genreFromInpxIsKeptForBookGenreRelation(@TempDir Path root) {
        InpxBookNormalizer normalizer = new InpxBookNormalizer(new HashMap<>(), new HashMap<>());
        Map<String, Genre> pendingGenres = new HashMap<>();
        InpxRecord record = new InpxRecord(Map.of(
                "AUTHOR", "Іваненко,Іван,",
                "GENRE", "sf:fantasy",
                "TITLE", "Жанрова книга",
                "FILE", "42",
                "EXT", "fb2",
                "LIBID", "42"
        ), "books.inp", "");

        InpxBookNormalizer.NormalizedBook result = normalizer.normalize(
                record, new HashMap<>(), pendingGenres, root, new HashMap<>(), "test-source", false);

        assertThat(result).isNotNull();
        assertThat(result.withoutGenre()).isFalse();
        Object genresValue = result.row()[20];
        assertThat(genresValue).isInstanceOf(java.util.List.class);
        java.util.List<?> genres = (java.util.List<?>) genresValue;
        assertThat(genres).hasSize(2);
        assertThat(genres.get(0)).isEqualTo("sf");
        assertThat(genres.get(1)).isEqualTo("fantasy");
        assertThat(pendingGenres.keySet()).contains("sf", "fantasy");
    }
}
