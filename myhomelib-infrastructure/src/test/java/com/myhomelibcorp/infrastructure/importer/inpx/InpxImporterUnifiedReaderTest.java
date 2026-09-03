package com.myhomelibcorp.infrastructure.importer.inpx;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.importengine.InpxReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InpxImporterUnifiedReaderTest {
    private static final char D = 0x04;

    @Test
    void standaloneInpUsesUnifiedReaderAndProducesArchiveAwareBookFile(@TempDir Path dir) throws Exception {
        Path inp = dir.resolve("catalog.inp");
        String row = String.join(String.valueOf(D),
                "Дорничев,Дмитрий,", "sf", "Книга", "Серія", "8", "book", "123",
                "lib1", "0", "fb2", "2026", "ru", "magic") + "\n";
        Files.writeString(inp, row, StandardCharsets.UTF_8);

        AuthorRepository authors = mock(AuthorRepository.class);
        GenreRepository genres = mock(GenreRepository.class);
        when(genres.findAll()).thenReturn(List.of());
        when(genres.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(authors.findByName(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(authors.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        InpxImporter importer = new InpxImporter();
        ReflectionTestUtils.setField(importer, "authorRepository", authors);
        ReflectionTestUtils.setField(importer, "genreRepository", genres);
        ReflectionTestUtils.setField(importer, "inpxReader", new InpxReader());

        Book book;
        try (var stream = importer.importBooks(inp)) {
            book = stream.findFirst().orElseThrow();
        }

        assertThat(book.getTitle()).isEqualTo("Книга");
        assertThat(book.getAuthors()).extracting(Author::getFullName).containsExactly("Дорничев Дмитрий");
        assertThat(book.getFileName()).isEqualTo("book.fb2");
        assertThat(book.getFolder()).isEqualTo("catalog.zip");
        assertThat(book.getArchiveEntry()).isEqualTo("book.fb2");
        assertThat(importer.countBooks(inp)).isEqualTo(1);
    }
}
