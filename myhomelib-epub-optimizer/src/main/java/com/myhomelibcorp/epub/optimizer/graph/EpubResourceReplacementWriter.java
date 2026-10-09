package com.myhomelibcorp.epub.optimizer.graph;

import com.myhomelibcorp.epub.optimizer.archive.EpubZipTransformSupport;
import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Replaces existing binary resources in-place by ZIP path; resource names and manifest references do not change. */
@Component
public final class EpubResourceReplacementWriter {
    public int replace(Path source, Path target, Map<String, Path> replacements) throws Exception {
        Map<String, Path> safe = replacements == null ? Map.of() : Map.copyOf(replacements);
        Path normalizedSource = EpubPathSafety.requireSafeRegularFile(source, "EPUB replacement source");
        Path normalizedTarget = EpubPathSafety.prepareTarget(target, "EPUB replacement target");
        if (safe.isEmpty()) {
            EpubPathSafety.copyFreshFile(normalizedSource, normalizedTarget, "EPUB replacement passthrough");
            return 0;
        }
        Set<String> used = new HashSet<>();
        EpubZipTransformSupport.rewrite(
                normalizedSource, normalizedTarget, "EPUB replacement target", Set.of(),
                "EPUB resource replacement cancelled",
                (zip, entry, output) -> {
                    Path replacement = safe.get(entry.getName());
                    if (replacement == null) return;
                    used.add(entry.getName());
                    Path safeReplacement = EpubPathSafety.requireSafeRegularFile(
                            replacement, "EPUB replacement resource");
                    try (InputStream in = Files.newInputStream(safeReplacement, LinkOption.NOFOLLOW_LINKS)) {
                        output.writeStream(in);
                    }
                });
        if (used.size() != safe.size()) {
            throw new IllegalArgumentException("Replacement map contains resources that are not present in the EPUB");
        }
        return safe.size();
    }

}
