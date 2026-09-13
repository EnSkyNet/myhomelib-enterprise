package com.myhomelibcorp.application.webreader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import java.io.IOException;
import java.util.Optional;

public interface WebReaderUseCase {
    Optional<WebReaderDocument> open(String bookId, int requestedChapter) throws IOException;
    ReadingProgressDto saveProgress(String bookId, int chapterIndex, long absoluteOffset) throws IOException;
}
