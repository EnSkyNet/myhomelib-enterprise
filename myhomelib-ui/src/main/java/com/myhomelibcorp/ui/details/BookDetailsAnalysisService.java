package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.group.LoadBookGroupsUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.inspection.BookInspectionService;
import com.myhomelibcorp.reader.inspection.DocumentInspection;
import com.myhomelibcorp.reader.inspection.DocumentInspectionSession;
import com.myhomelibcorp.ui.details.model.RichBookDetailsSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookDetailsAnalysisService {

    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final ResolveBookContentUseCase resolveBookContentUseCase;
    private final LoadBookGroupsUseCase loadBookGroupsUseCase;
    private final BookInspectionService inspectionService = new BookInspectionService();

    public RichBookDetailsSession analyze(String bookId) {
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("Book id is required");
        BookDto fullBook = loadBookByIdUseCase.execute(BookId.fromString(bookId))
                .orElseThrow(() -> new IllegalArgumentException("Книгу не знайдено: " + bookId));

        var groups = loadBookGroupsUseCase.execute(fullBook.getId());

        if (!fullBook.isLocal()) {
            DocumentInspection unavailable = DocumentInspection.unsupported(formatOf(fullBook),
                    "Файл ще не завантажено локально");
            return new RichBookDetailsSession(fullBook, unavailable, groups, null, null);
        }

        try {
            ResolvedBookContent source = resolveBookContentUseCase.execute(
                    fullBook, ResolveBookContentUseCase.DETAILS_EXTENSIONS);
            DocumentInspectionSession inspection = inspectionService.inspect(
                    new FileBookSource(source.path(), fullBook.getId()));
            return new RichBookDetailsSession(fullBook, inspection.inspection(), groups, inspection, source);
        } catch (Exception e) {
            DocumentInspection unavailable = DocumentInspection.unsupported(formatOf(fullBook),
                    "Файл недоступний для аналізу: " + safeMessage(e));
            return new RichBookDetailsSession(fullBook, unavailable, groups, null, null);
        }
    }

    private static String formatOf(BookDto book) {
        String name = book.getArchiveEntry();
        if (name == null || name.isBlank()) name = book.getFileName();
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).toUpperCase() : "";
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
