package com.myhomelibcorp.application.usecase.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** Removes downloaded bytes while preserving the online catalog entry and user state. */
@Component
@RequiredArgsConstructor
public class RemoveLocalBookCopyUseCase {
    private final BookResourcePort resources;
    private final BookQueryRepository queries;
    private final BookCommandRepository commands;

    /**
     * @return number of catalog rows switched to non-local. For a shared archive this
     *         includes every book whose physical bytes live in the deleted archive.
     */
    public int execute(BookDto book) throws Exception {
        if (book == null || book.getId() == null || book.getId().isBlank()) {
            throw new IllegalArgumentException("Book is required");
        }

        Path physical = resources.locateBookFile(
                book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry()).orElse(null);
        boolean archived = book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank();

        List<Book> affected = archived
                ? queries.findByArchiveContainer(book.getCollectionRoot(), book.getFolder(), physical == null ? "" : physical.toString())
                : List.of();

        if (physical != null) resources.deletePhysicalFile(physical);

        if (archived && !affected.isEmpty()) {
            for (Book row : affected) {
                commands.updateStorage(row.getId(), row.getCollectionRoot(), row.getFolder(),
                        row.getFileName(), row.getArchiveEntry(), false);
            }
            return affected.size();
        }

        commands.updateStorage(BookId.fromString(book.getId()),
                book.getCollectionRoot(), book.getFolder(), book.getFileName(), book.getArchiveEntry(), false);
        return 1;
    }
}
