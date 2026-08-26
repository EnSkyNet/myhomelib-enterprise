package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.action.BookActionRunResult;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Runs one named book action off the JavaFX thread against a fully loaded catalogue book. */
@Component
@RequiredArgsConstructor
public class RunBookActionUseCase {
    private final ExecutorPort executorPort;
    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final ResolveBookContentUseCase resolveBookContentUseCase;
    private final BookActionProfileService profileService;
    private final BookActionExecutionService executionService;

    public CompletableFuture<BookActionRunResult> execute(BookId bookId, String profileId) {
        return executorPort.submit(() -> run(bookId, profileId));
    }

    private BookActionRunResult run(BookId bookId, String profileId) {
        BookActionProfile profile = profileService.findById(profileId).orElse(null);
        if (profile == null) return BookActionRunResult.failure(0, "Профіль дії не знайдений");
        BookDto book = loadBookByIdUseCase.execute(bookId).orElse(null);
        if (book == null) return BookActionRunResult.failure(0, "Книга не знайдена");

        ResolvedBookContent content = null;
        try {
            content = resolveBookContentUseCase.execute(book, ResolveBookContentUseCase.DETAILS_EXTENSIONS);
            Path file = content.path().toAbsolutePath().normalize();
            Map<String, String> placeholders = placeholders(book, file);
            BookActionRunResult result = executionService.execute(profile, placeholders);

            // A detached process may still need the extracted archive entry after ProcessBuilder.start().
            // Retain only that temporary materialization until JVM exit; waited profiles can clean immediately.
            boolean detached = profile.commands().stream().anyMatch(c -> !c.waitForExit());
            if (content.temporary() && detached) {
                file.toFile().deleteOnExit();
                content = null;
            }
            return result;
        } catch (Exception e) {
            return BookActionRunResult.failure(0, e.getMessage());
        } finally {
            if (content != null) content.close();
        }
    }

    public static Map<String, String> placeholders(BookDto book, Path file) {
        Path parent = file.getParent() == null ? file.toAbsolutePath().getParent() : file.getParent();
        String dir = parent == null ? "" : parent.toString();
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        String ext = extension(fileName);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("%FILE%", file.toString());
        values.put("%FILENAME%", fileName);
        values.put("%DIR%", dir);
        values.put("%DEST%", dir); // compatibility with the legacy export post-command
        values.put("%DESTFILE%", file.toString());
        values.put("%TMP%", Path.of(System.getProperty("java.io.tmpdir", dir)).toAbsolutePath().toString());
        values.put("%TITLE%", text(book.getTitle()));
        values.put("%AUTHOR%", text(book.getAuthorsText()));
        values.put("%SERIES%", text(book.getSeries()));
        values.put("%LANG%", text(book.getLanguage()));
        values.put("%ISBN%", text(book.getIsbn()));
        values.put("%PUBLISHER%", text(book.getPublisher()));
        values.put("%YEAR%", book.getYear() == null ? "" : book.getYear().toString());
        values.put("%EXT%", ext);
        values.put("%BOOKID%", text(book.getId()));
        values.put("%COLLECTION%", text(book.getCollectionRoot()));
        return Map.copyOf(values);
    }

    private static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static String text(String value) { return value == null ? "" : value; }
}
