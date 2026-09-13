package com.myhomelibcorp.application.bulkedit;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

public record BatchMetadataPreviewItem(
        BookId bookId,
        BatchMetadataEditableSnapshot before,
        BatchMetadataEditableSnapshot after,
        List<BatchMetadataEditField> changedFields) {
    public BatchMetadataPreviewItem {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }
}
