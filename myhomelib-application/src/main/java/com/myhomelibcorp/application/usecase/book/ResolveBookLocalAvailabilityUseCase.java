package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves effective local state from the physical resource, not from the cached DB flag. */
@Component
@RequiredArgsConstructor
public class ResolveBookLocalAvailabilityUseCase {
    private final BookResourcePort bookResourcePort;

    public boolean execute(BookListItem book) {
        if (book == null) return false;
        return bookResourcePort.locateBookFile(
                book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry()).isPresent();
    }
}
