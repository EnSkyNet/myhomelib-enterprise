package com.myhomelibcorp.application.port.out.statistics;

import com.myhomelibcorp.application.dto.AuthorBookStatistics;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;

public interface AuthorBookStatisticsPort {
    AuthorBookStatistics load(AuthorId authorId);
}
