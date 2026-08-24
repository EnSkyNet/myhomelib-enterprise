package com.myhomelibcorp.application.usecase.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.download.OnlineBookDownloadPort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
public class DownloadBookUseCase {
    private final OnlineBookDownloadPort downloadPort;
    private final BookCommandRepository bookCommandRepository;

    public Path execute(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress) throws Exception {
        if (book == null) throw new IllegalArgumentException("Book is required");
        if (collection == null) throw new IllegalStateException("Активну колекцію не вибрано");
        var result = downloadPort.download(book, collection, cancelFlag, progress == null ? v -> {} : progress);
        bookCommandRepository.updateStorage(
                BookId.fromString(book.getId()),
                result.root().toString(), result.folder(), result.fileName(), result.archiveEntry(), true);
        return result.physicalPath();
    }
}
