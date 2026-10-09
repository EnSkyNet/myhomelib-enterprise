package com.myhomelibcorp.epub.optimizer.graph;

import com.myhomelibcorp.epub.optimizer.archive.EpubZipTransformSupport;
import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Removes only graph-proven orphan leaf resources and their OPF manifest items. */
@Component
public final class EpubResourcePruner {
    public int prune(Path source, Path target, EpubResourceGraph graph, Set<String> requested) throws Exception {
        Objects.requireNonNull(graph, "graph");
        source = EpubPathSafety.requireSafeRegularFile(source, "EPUB prune source");
        target = EpubPathSafety.prepareTarget(target, "EPUB prune target");
        Set<String> safe = new LinkedHashSet<>(graph.safelyPrunableOrphans());
        safe.retainAll(requested == null ? Set.of() : requested);
        if (safe.isEmpty()) {
            EpubPathSafety.copyFreshFile(source, target, "EPUB prune passthrough");
            return 0;
        }
        EpubZipTransformSupport.rewrite(
                source, target, "EPUB prune target", safe, "EPUB optimization cancelled",
                (zip, entry, output) -> {
                    if (entry.getName().equals(graph.packagePath())) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            byte[] replacement = rewritePackage(
                                    in, EpubReferencePaths.parent(graph.packagePath()), safe);
                            output.writeBytes(replacement);
                        }
                    }
                });
        return safe.size();
    }

    private byte[] rewritePackage(InputStream input, String base, Set<String> removedPaths) throws Exception {
        return EpubXmlRewriteSupport.rewrite(
                input,
                href -> removedPaths.contains(EpubReferencePaths.resolveReference(base, href)),
                (name, value) -> value);
    }

}
