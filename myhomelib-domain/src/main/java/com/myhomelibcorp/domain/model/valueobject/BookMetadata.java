package com.myhomelibcorp.domain.model.valueobject;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BookMetadata {
    String annotation;
    String keywords;
    LanguageCode language;
    Isbn isbn;
    String review;
    int rate;
    int progress;

    public static BookMetadata empty() {
        return BookMetadata.builder()
                .language(LanguageCode.of("uk"))
                .rate(0)
                .progress(0)
                .build();
    }
}