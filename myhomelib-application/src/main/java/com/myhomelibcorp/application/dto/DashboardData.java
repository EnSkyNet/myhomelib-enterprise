package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardData {
    private BookDto continueReading;
    private List<BookDto> recentBooks;
    private List<BookDto> recentAdded;
    private List<AuthorDto> favoriteAuthors;  // <-- має бути List<AuthorDto>
    private LibraryStatistics statistics;
}