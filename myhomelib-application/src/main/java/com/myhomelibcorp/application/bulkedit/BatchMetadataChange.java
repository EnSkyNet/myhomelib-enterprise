package com.myhomelibcorp.application.bulkedit;

import com.myhomelibcorp.domain.model.valueobject.BookId;

public record BatchMetadataChange(
        BookId bookId,
        BatchMetadataEditableSnapshot before,
        BatchMetadataEditableSnapshot after) {
}
