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
    @Test
    void catalogPreviewMatchesFullNormalizationForRealisticOnlineRecord(@TempDir Path root) {
        InpxBookNormalizer normalizer = new InpxBookNormalizer(new HashMap<>(), new HashMap<>());
        InpxRecord record = new InpxRecord(Map.ofEntries(
                Map.entry("AUTHOR", "Дорничев,Дмитрий,Александрович:"),
                Map.entry("GENRE", "sf:fantasy"),
                Map.entry("TITLE", "Тестова книга"),
                Map.entry("SERIES", "Серія"),
                Map.entry("SERNO", "2"),
                Map.entry("FILE", "98266"),
                Map.entry("SIZE", "123456"),
                Map.entry("LIBID", "98266"),
                Map.entry("DEL", "0"),
                Map.entry("EXT", "fb2"),
                Map.entry("DATE", "2020-01-01"),
                Map.entry("LANG", "ru"),
                Map.entry("LIBRATE", "87"),
                Map.entry("KEYWORDS", "space, future")
        ), "online.inp", "online.zip");

        String sourceMarker = "catalog:test-source";
        InpxBookNormalizer.CatalogPreview preview = normalizer.preview(record, sourceMarker);
        InpxBookNormalizer.NormalizedBook full = normalizer.normalize(
                record, new HashMap<>(), new HashMap<>(), root, new HashMap<>(), sourceMarker,
                true, java.util.Set.of(), true);

        assertThat(preview).isNotNull();
        assertThat(full).isNotNull();
        assertThat(preview.catalogSnapshot()).isEqualTo(full.catalogSnapshot());
        assertThat(preview.catalogSnapshot().catalogFingerprint())
                .isEqualTo("5c9b284ab016ed5e93d937bfc86ed48840569a1d2a63c48981b7c478180fb55a");
        assertThat(preview.deleted()).isEqualTo(full.explicitlyDeleted());
        assertThat(preview.withoutAuthor()).isEqualTo(full.withoutAuthor());
        assertThat(preview.withoutGenre()).isEqualTo(full.withoutGenre());
        assertThat(normalizer.previewSearchFingerprint(preview)).isEqualTo(full.searchFingerprint());
        assertThat(normalizer.previewLocal(preview, root, new HashMap<>(), true, java.util.Set.of()))
                .isEqualTo(((Number) full.row()[16]).intValue() != 0);
    }

    @Test
    void catalogPreviewMissingMetadataFlagsMatchFullNormalization(@TempDir Path root) {
        InpxBookNormalizer normalizer = new InpxBookNormalizer(new HashMap<>(), new HashMap<>());
        InpxRecord record = new InpxRecord(Map.of(
                "AUTHOR", ":",
                "GENRE", "::",
                "TITLE", "Без метаданих",
                "FILE", "missing",
                "EXT", "fb2",
                "LIBID", "missing",
                "DEL", "1"
        ), "online.inp", "online.zip");

        InpxBookNormalizer.CatalogPreview preview = normalizer.preview(record, "catalog:test");
        InpxBookNormalizer.NormalizedBook full = normalizer.normalize(
                record, new HashMap<>(), new HashMap<>(), root, new HashMap<>(), "catalog:test",
                true, java.util.Set.of(), true);

        assertThat(preview).isNotNull();
        assertThat(preview.withoutAuthor()).isTrue();
        assertThat(preview.withoutGenre()).isTrue();
        assertThat(preview.deleted()).isTrue();
        assertThat(full).isNotNull();
        assertThat(preview.catalogSnapshot()).isEqualTo(full.catalogSnapshot());
        assertThat(preview.withoutAuthor()).isEqualTo(full.withoutAuthor());
        assertThat(preview.withoutGenre()).isEqualTo(full.withoutGenre());
    }

    @Test
    void remoteOnlyOnlineFb2DoesNotCreatePlatformPathWhenPreScanIsEmpty(@TempDir Path root) {
        InpxBookNormalizer normalizer = new InpxBookNormalizer(new HashMap<>(), new HashMap<>());
        InpxRecord record = new InpxRecord(Map.of(
                "AUTHOR", "Автор,Тест,",
                "GENRE", "sf",
                "TITLE", "Назва\uD800",
                "FILE", "encoding-test",
                "EXT", "fb2",
                "LIBID", "encoding-test"
        ), "online.inp", "online.zip");

        InpxBookNormalizer.NormalizedBook result = normalizer.normalize(
                record, new HashMap<>(), new HashMap<>(), root, new HashMap<>(), "catalog:test",
                true, java.util.Set.of(), true);

        assertThat(result).isNotNull();
        assertThat(result.row()[16]).isEqualTo(0);
    }

}
