package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadingProgressDto {
    private String bookId;
    private String paragraphId;
    private int charOffset;
    private double percent;
    private LocalDateTime updatedAt;
}