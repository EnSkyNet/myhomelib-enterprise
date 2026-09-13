package com.myhomelibcorp.application.duplicate.fuzzy;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.Isbn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FuzzyDuplicateDetectorEvaluationTest {
    private final FuzzyDuplicateDetector detector = new FuzzyDuplicateDetector();

    @Test
    void labeledEvaluationCorpusMeetsPrecisionAndRecallTarget() {
        List<Case> corpus = List.of(
                positive("The Hobbit", "J. R. R. Tolkien", 1937, null, "The Hobbit", "J R R Tolkien", 1937, null),
                positive("Harry Potter and the Philosopher's Stone", "J. K. Rowling", 1997, null,
                        "Harry Potter & the Philosopher’s Stone", "J K Rowling", 1997, null),
                positive("Clean Code", "Robert C. Martin", 2008, null, "Clean Cod", "Robert C Martin", 2008, null),
                positive("Місто", "Валер'ян Підмогильний", 1928, null, "Місто.", "Валер’ян Підмогильний", 1928, null),
                positive("Domain-Driven Design", "Eric Evans", 2003, null, "Domain Driven Design", "Eric Evans", 2004, null),
                positive("Different localized title", "Some Author", 2000, "9780306406157",
                        "Completely different title", "Other Display Name", 2010, "9780306406157"),
                positive("Refactoring: Improving the Design of Existing Code", "Martin Fowler", 1999, null,
                        "Refactoring Improving the Design of Existing Code", "Martin Fowler", 1999, null),
                positive("The Pragmatic Programmer", "Andrew Hunt", 1999, null,
                        "The Pragmatic Programmer", "Andrew Hunt", 2000, null),
                negative("Common Title", "Alice Writer", 2020, "Common Title", "Bob Writer", 2020),
                negative("Java in Practice", "Brian Goetz", 2006, "Java Concurrency in Practice", "Brian Goetz", 2006),
                negative("Effective Java", "Joshua Bloch", 2018, "Effective Python", "Joshua Bloch", 2018),
                negative("The Stand", "Stephen King", 1978, "It", "Stephen King", 1986),
                negative("Dune", "Frank Herbert", 1965, "Dune Messiah", "Frank Herbert", 1969),
                negative("Foundation", "Isaac Asimov", 1951, "Foundation's Edge", "Isaac Asimov", 1982),
                negative("Майстер і Маргарита", "Михайло Булгаков", 1967, "Майстер корабля", "Юрій Яновський", 1928),
                negative("Design Patterns", "Erich Gamma", 1994, "Patterns of Enterprise Application Architecture", "Martin Fowler", 2002)
        );

        int tp = 0, fp = 0, fn = 0;
        for (Case c : corpus) {
            boolean predicted = detector.evaluate(c.left(), c.right()).isPresent();
            if (predicted && c.duplicate()) tp++;
            else if (predicted) fp++;
            else if (c.duplicate()) fn++;
        }
        double precision = tp == 0 ? 0 : (double) tp / (tp + fp);
        double recall = tp == 0 ? 0 : (double) tp / (tp + fn);
        System.out.printf("MHL-105 evaluation: tp=%d fp=%d fn=%d precision=%.3f recall=%.3f%n",
                tp, fp, fn, precision, recall);

        assertThat(precision).as("evaluation precision").isGreaterThanOrEqualTo(0.95);
        assertThat(recall).as("evaluation recall").isGreaterThanOrEqualTo(0.90);
    }

    @Test
    void suggestionContainsScoreAndReasonsAndNeverMutatesBooks() {
        Book source = book("10000000-0000-0000-0000-000000000001", "Clean Code", "Robert C. Martin", 2008, null);
        Book candidate = book("10000000-0000-0000-0000-000000000002", "Clean Cod", "Robert C Martin", 2008, null);
        String originalTitle = source.getTitle();

        var match = detector.evaluate(source, candidate).orElseThrow();

        assertThat(match.score()).isBetween(FuzzyDuplicateDetector.DEFAULT_THRESHOLD, 1.0);
        assertThat(match.reasons()).contains(FuzzyDuplicateReason.TITLE_SIMILAR, FuzzyDuplicateReason.AUTHOR_EXACT,
                FuzzyDuplicateReason.YEAR_MATCH);
        assertThat(source.getTitle()).isEqualTo(originalTitle);
        assertThat(candidate.getTitle()).isEqualTo("Clean Cod");
    }

    @Test
    void commonTitleWithDifferentAuthorIsNotSuggested() {
        assertThat(detector.evaluate(
                book("20000000-0000-0000-0000-000000000001", "Common Title", "Alice Writer", 2020, null),
                book("20000000-0000-0000-0000-000000000002", "Common Title", "Bob Writer", 2020, null)))
                .isEmpty();
    }

    private static Case positive(String t1, String a1, Integer y1, String i1,
                                 String t2, String a2, Integer y2, String i2) {
        return new Case(book(nextId(), t1, a1, y1, i1), book(nextId(), t2, a2, y2, i2), true);
    }

    private static Case negative(String t1, String a1, Integer y1, String t2, String a2, Integer y2) {
        return new Case(book(nextId(), t1, a1, y1, null), book(nextId(), t2, a2, y2, null), false);
    }

    private static int idSequence;
    private static String nextId() {
        int n = ++idSequence;
        return String.format("30000000-0000-0000-0000-%012d", n);
    }

    private static Book book(String id, String title, String authorName, Integer year, String isbn) {
        String[] parts = authorName.trim().split("\\s+");
        String first = parts.length > 1 ? String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1)) : "";
        String last = parts[parts.length - 1];
        BookMetadata metadata = BookMetadata.builder()
                .year(year)
                .isbn(isbn == null ? null : Isbn.of(isbn))
                .build();
        return Book.builder()
                .id(BookId.fromString(id))
                .title(title)
                .authors(List.of(new Author(first, "", last)))
                .metadata(metadata)
                .file(BookFile.empty())
                .build();
    }

    private record Case(Book left, Book right, boolean duplicate) {}
}
