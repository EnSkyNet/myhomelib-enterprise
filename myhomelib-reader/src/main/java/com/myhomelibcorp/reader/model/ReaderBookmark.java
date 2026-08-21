// myhomelib-reader/src/main/java/com/myhomelibcorp/reader/model/ReaderBookmark.java
package com.myhomelibcorp.reader.model;

import com.myhomelibcorp.reader.api.ReaderPosition;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ReaderBookmark {
    String id;
    String bookId;
    ReaderPosition position;
    String title;
    String note;
    @Builder.Default
    LocalDateTime createdAt = LocalDateTime.now();
}