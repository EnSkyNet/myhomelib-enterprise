package com.myhomelibcorp.application.usecase.author;

import com.myhomelibcorp.application.dto.AuthorBookStatistics;
import com.myhomelibcorp.application.port.out.statistics.AuthorBookStatisticsPort;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoadAuthorBookStatisticsUseCase {
    private final AuthorBookStatisticsPort statisticsPort;

    public AuthorBookStatistics execute(AuthorId authorId) {
        return authorId == null ? AuthorBookStatistics.empty() : statisticsPort.load(authorId);
    }
}
