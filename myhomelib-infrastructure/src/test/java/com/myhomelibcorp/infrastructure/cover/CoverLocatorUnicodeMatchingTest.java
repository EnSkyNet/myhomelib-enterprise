package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoverLocatorUnicodeMatchingTest {
    private final ZipArchiveReader archiveReader = mock(ZipArchiveReader.class);
    private final CoverLocatorImpl locator = new CoverLocatorImpl(archiveReader, mock(BookResourcePort.class));
    private final Path archive = Path.of("fixture.fb2.zip");

    private Optional<String> match(BookDto book, List<String> entries) {
        when(archiveReader.listEntries(archive)).thenReturn(entries);
        return locator.locateCoverInArchive(book, archive);
    }

    @Test
    void preservesUkrainianLettersPreviouslyRemovedByAsciiCyrillicNormalizer() {
        // Before this fix, "ЇЄІҐ" normalized to an empty string and matched notes.txt.
        BookDto book = BookDto.builder().title("ЇЄІҐ").build();

        assertThat(match(book, List.of("notes.txt", "їєіґ.fb2")))
                .contains("їєіґ.fb2");
    }

    @Test
    void preservesOtherUnicodeScriptsWhenMatchingTitles() {
        BookDto book = BookDto.builder().title("日本語").build();

        assertThat(match(book, List.of("other.fb2", "folder/日本語.fb2")))
                .contains("folder/日本語.fb2");
    }

    @Test
    void emptyNormalizedArchiveEntryDoesNotMatchEveryFileName() {
        BookDto book = BookDto.builder().archiveEntry("!!!").fileName("ІЇЄ.fb2").build();

        assertThat(match(book, List.of("notes.txt", "ІЇЄ.fb2")))
                .contains("ІЇЄ.fb2");
    }

    @Test
    void punctuationOnlyTitleDoesNotMatchArbitraryNonBookEntry() {
        BookDto book = BookDto.builder().title("!!!").build();

        assertThat(match(book, List.of("notes.txt", "actual.fb2")))
                .contains("actual.fb2");
    }

    @Test
    void matchesLateUnicodeEntryAcrossLargeArchiveWithoutChangingEntryOrder() {
        ArrayList<String> entries = new ArrayList<>(10_001);
        for (int i = 0; i < 10_000; i++) entries.add("other-" + i + ".fb2");
        entries.add("Ґанок_Історія.fb2");

        BookDto book = BookDto.builder().fileName("Ґанок Історія.fb2").build();

        assertThat(match(book, entries)).contains("Ґанок_Історія.fb2");
    }

    @Test
    void retainsLegacyLatinAndCyrillicPunctuationMatching() {
        BookDto book = BookDto.builder().fileName("My - Book_Книга.fb2").build();

        assertThat(match(book, List.of("other.fb2", "mybookкнига.fb2")))
                .contains("mybookкнига.fb2");
    }
}
