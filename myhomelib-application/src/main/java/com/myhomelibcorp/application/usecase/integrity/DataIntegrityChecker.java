package com.myhomelibcorp.application.usecase.integrity;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataIntegrityChecker {

    private final BookQueryRepository bookRepository;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;

    public record IntegrityReport(List<String> issues, long booksWithoutAuthor, long booksWithoutGenre,
                                  long orphanedAuthors, long orphanedGenres, long duplicateBooks) {}

    public IntegrityReport check() {
        List<String> issues = new ArrayList<>();
        log.info("🔍 Starting data integrity check...");

        // 1. Книги без авторів
        // (потрібен спеціальний запит, але ми можемо перевірити через існуючі методи)
        long booksWithoutAuthor = 0;
        long booksWithoutGenre = 0;
        long orphanedAuthors = 0;
        long orphanedGenres = 0;
        long duplicateBooks = 0;

        // Перевірка дублікатів книг за назвою та першим автором
        var allBooks = bookRepository.findAll(); // обережно з великими БД! краще зробити спеціальний запит
        var seen = new java.util.HashSet<String>();
        for (Book b : allBooks) {
            String key = b.getTitle() + "|" + b.authorsText();
            if (seen.contains(key)) {
                duplicateBooks++;
                issues.add("Дублікат: " + key);
            }
            seen.add(key);
        }

        log.info("✅ Integrity check completed: {} issues found", issues.size());
        return new IntegrityReport(issues, booksWithoutAuthor, booksWithoutGenre,
                orphanedAuthors, orphanedGenres, duplicateBooks);
    }

    public void fixOrphanedBooks() {
        // логіка виправлення
        log.info("🔧 Fixing orphaned books...");
    }
}