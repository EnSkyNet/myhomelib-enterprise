package com.myhomelibcorp.application.dto;

import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BookFilter {
    private AuthorId authorId;
    private String seriesName;
    private String genreCode;
    private Long groupId;
    private String searchText;
    private Integer limit;
    private Integer offset;

    public boolean isEmpty() {
        return authorId == null
                && (seriesName == null || seriesName.isBlank())
                && (genreCode == null || genreCode.isBlank())
                && groupId == null
                && (searchText == null || searchText.isBlank());
    }
}