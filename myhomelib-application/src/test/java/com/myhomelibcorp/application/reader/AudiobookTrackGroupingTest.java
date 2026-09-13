package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AudiobookTrackGroupingTest {
    @Test void groupsOnlyExplicitMatchingGroupAndSortsByTrackNumber() throws Exception {
        Path dir = Files.createTempDirectory("audio-group");
        Path one = Files.write(dir.resolve("one.mp3"), new byte[]{1});
        Path two = Files.write(dir.resolve("two.mp3"), new byte[]{2});
        Path alternate = Files.write(dir.resolve("alternate.m4b"), new byte[]{3});
        try {
            BookArtifact a1 = artifact("a1", one, "mp3", Map.of("audiobookGroup","g1","trackNumber","2"));
            BookArtifact a2 = artifact("a2", two, "mp3", Map.of("audiobookGroup","g1","trackNumber","1"));
            BookArtifact alt = artifact("alt", alternate, "m4b", Map.of());
            Book book = Book.builder().id(BookId.generate()).title("Audio").metadata(BookMetadata.empty())
                    .file(a1.getFile()).artifacts(List.of(a1,a2,alt)).preferredArtifactId("a1").local(true).build();
            List<Path> result = AudiobookTrackGrouping.resolve(book, one, selected -> Optional.of(Path.of(selected.getFile().getFullPath())));
            assertThat(result).containsExactly(two.toAbsolutePath().normalize(), one.toAbsolutePath().normalize());
            assertThat(result).doesNotContain(alternate);
        } finally { Files.deleteIfExists(one); Files.deleteIfExists(two); Files.deleteIfExists(alternate); Files.deleteIfExists(dir); }
    }

    @Test void untaggedAlternativeRemainsSingleRepresentation() throws Exception {
        Path dir = Files.createTempDirectory("audio-alt");
        Path mp3 = Files.write(dir.resolve("book.mp3"), new byte[]{1});
        Path m4b = Files.write(dir.resolve("book.m4b"), new byte[]{2});
        try {
            BookArtifact a1 = artifact("mp3", mp3, "mp3", Map.of());
            BookArtifact a2 = artifact("m4b", m4b, "m4b", Map.of());
            Book book = Book.builder().id(BookId.generate()).title("Audio").metadata(BookMetadata.empty())
                    .file(a1.getFile()).artifacts(List.of(a1,a2)).preferredArtifactId("mp3").local(true).build();
            assertThat(AudiobookTrackGrouping.resolve(book, mp3, selected -> Optional.of(Path.of(selected.getFile().getFullPath()))))
                    .containsExactly(mp3);
        } finally { Files.deleteIfExists(mp3); Files.deleteIfExists(m4b); Files.deleteIfExists(dir); }
    }

    private static BookArtifact artifact(String id, Path path, String format, Map<String,String> metadata) {
        BookFile file = new BookFile(path.getFileName().toString(), path.getParent().toString(), "", 1, "");
        return BookArtifact.builder().id(id).name(path.getFileName().toString()).format(format).file(file)
                .local(true).remote(false).metadata(metadata).build();
    }
}
