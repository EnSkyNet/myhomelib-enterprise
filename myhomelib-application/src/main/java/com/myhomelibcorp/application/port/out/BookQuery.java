package com.myhomelibcorp.application.port.out;

import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BookQuery {
    private AuthorId authorId;
    private String seriesName;
    private String genreCode;
    private Long groupId;
    private String searchText;
    private int limit;
    private int offset;
}