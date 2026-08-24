package com.myhomelibcorp.application.port.out.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.collection.Collection;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

public interface OnlineBookDownloadPort {
    DownloadedBook download(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress) throws Exception;

    record DownloadedBook(Path root, String folder, String fileName, String archiveEntry, Path physicalPath) { }
}
