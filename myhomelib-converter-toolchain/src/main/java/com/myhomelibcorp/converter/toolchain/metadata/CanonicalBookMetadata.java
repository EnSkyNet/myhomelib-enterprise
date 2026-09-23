package com.myhomelibcorp.converter.toolchain.metadata;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.Cover;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable conversion-time metadata snapshot owned by MyHomeLib.
 *
 * <p>The snapshot is intentionally taken before an external converter is invoked so a multi-step
 * route cannot progressively lose catalog metadata.  It is a merge source, not an instruction to
 * overwrite richer metadata already present in a converted document.</p>
 */
public record CanonicalBookMetadata(
        String title,
        List<CanonicalAuthor> authors,
        List<String> genres,
        String series,
        Integer sequenceNumber,
        String annotation,
        String language,
        String isbn,
        Integer year,
        String publisher,
        String translators,
        CanonicalCover cover
) {
    public CanonicalBookMetadata {
        title = clean(title);
        authors = List.copyOf(Objects.requireNonNullElse(authors, List.of()));
        genres = List.copyOf(Objects.requireNonNullElse(genres, List.of()));
        series = clean(series);
        annotation = clean(annotation);
        language = clean(language);
        isbn = clean(isbn);
        publisher = clean(publisher);
        translators = clean(translators);
    }

    public static CanonicalBookMetadata from(Book book) {
        Objects.requireNonNull(book, "book");

        List<CanonicalAuthor> authors = new ArrayList<>();
        for (Author author : book.getAuthors()) {
            if (author == null) continue;
            CanonicalAuthor canonical = new CanonicalAuthor(
                    clean(author.getFirstName()),
                    clean(author.getMiddleName()),
                    clean(author.getLastName()));
            if (!canonical.isEmpty()) authors.add(canonical);
        }

        List<String> genres = new ArrayList<>();
        for (Genre genre : book.getGenres()) {
            if (genre == null) continue;
            String code = clean(genre.getFb2Code());
            if (!code.isBlank() && !genres.contains(code)) genres.add(code);
        }

        String language = book.getLanguage() == null ? "" : clean(book.getLanguage().value());
        String isbn = book.getIsbn() == null ? "" : clean(book.getIsbn().value());
        CanonicalCover cover = CanonicalCover.from(book.getCover());

        return new CanonicalBookMetadata(
                book.getTitle(), authors, genres, book.getSeries(), book.getSequenceNumber(),
                book.getAnnotation(), language, isbn, book.getYear(), book.getPublisher(),
                book.getTranslators(), cover);
    }

    public record CanonicalAuthor(String firstName, String middleName, String lastName) {
        public CanonicalAuthor {
            firstName = clean(firstName);
            middleName = clean(middleName);
            lastName = clean(lastName);
        }

        public boolean isEmpty() {
            return firstName.isBlank() && middleName.isBlank() && lastName.isBlank();
        }
    }

    public record CanonicalCover(String mimeType, byte[] data) {
        public CanonicalCover {
            mimeType = clean(mimeType);
            data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        }

        @Override public byte[] data() { return Arrays.copyOf(data, data.length); }
        public boolean isEmpty() { return data.length == 0; }
        int byteSize() { return data.length; }
        byte[] copyRange(int fromInclusive, int toExclusive) {
            if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > data.length) {
                throw new IndexOutOfBoundsException("Invalid cover byte range " + fromInclusive + ".." + toExclusive);
            }
            return Arrays.copyOfRange(data, fromInclusive, toExclusive);
        }

        static CanonicalCover from(Cover cover) {
            if (cover == null || cover.isEmpty()) return new CanonicalCover("", new byte[0]);
            return new CanonicalCover(cover.getMimeType(), cover.getData());
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
