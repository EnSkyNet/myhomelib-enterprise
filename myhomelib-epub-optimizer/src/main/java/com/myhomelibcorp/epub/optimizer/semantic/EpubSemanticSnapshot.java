package com.myhomelibcorp.epub.optimizer.semantic;

import java.util.List;
import java.util.Map;

public record EpubSemanticSnapshot(
        String packagePath,
        Map<String, String> metadata,
        List<String> spineOrder,
        Map<String, String> chapterTextHashes,
        String navigationTextHash) {
    public EpubSemanticSnapshot {
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        spineOrder = List.copyOf(spineOrder == null ? List.of() : spineOrder);
        chapterTextHashes = Map.copyOf(chapterTextHashes == null ? Map.of() : chapterTextHashes);
        navigationTextHash = navigationTextHash == null ? "" : navigationTextHash;
    }
}
