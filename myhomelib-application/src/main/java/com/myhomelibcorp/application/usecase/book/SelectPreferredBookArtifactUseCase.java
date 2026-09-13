package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Changes the preferred representation and returns the refreshed logical book. */
@Service
@RequiredArgsConstructor
public class SelectPreferredBookArtifactUseCase {
    private final BookCommandRepository commands;
    private final BookQueryRepository queries;
    private final BookMapper mapper;

    public BookDto execute(BookId bookId, String artifactId) {
        if (bookId == null) throw new IllegalArgumentException("Book id is required");
        commands.selectPreferredArtifact(bookId, artifactId);
        return queries.findById(bookId)
                .map(mapper::toDto)
                .orElseThrow(() -> new IllegalStateException("Book not found after artifact selection: " + bookId));
    }
}
