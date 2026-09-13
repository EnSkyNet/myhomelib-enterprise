package com.myhomelibcorp.application.bulkedit;

import java.util.List;

public record BatchMetadataEditPreview(int selectedCount, List<BatchMetadataPreviewItem> sample) {
    public BatchMetadataEditPreview {
        sample = sample == null ? List.of() : List.copyOf(sample);
    }
}
