package com.myhomelibcorp.application.usecase.reader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoadReadingProgressUseCase {

    private final ReadingProgressRepository readingProgressRepository;

    public Optional<ReadingProgressDto> execute(String bookId) {
        return readingProgressRepository.findByBookId(bookId);
    }
}