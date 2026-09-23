package com.myhomelibcorp.epub.optimizer.semantic;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class EpubSemanticVerifier {
    private final EpubSemanticSnapshotService snapshots;

    public EpubSemanticVerifier(EpubSemanticSnapshotService snapshots) { this.snapshots = snapshots; }

    public Verification verify(Path before, Path after) throws Exception {
        EpubSemanticSnapshot left = snapshots.snapshot(before);
        EpubSemanticSnapshot right = snapshots.snapshot(after);
        List<String> differences = new ArrayList<>();
        if (!left.metadata().equals(right.metadata())) differences.add("metadata");
        if (!left.spineOrder().equals(right.spineOrder())) differences.add("spine-order");
        if (!left.chapterTextHashes().equals(right.chapterTextHashes())) differences.add("chapter-text");
        if (!left.navigationTextHash().equals(right.navigationTextHash())) differences.add("navigation-text");
        return new Verification(differences.isEmpty(), List.copyOf(differences));
    }

    public record Verification(boolean equivalent, List<String> differences) { }
}
