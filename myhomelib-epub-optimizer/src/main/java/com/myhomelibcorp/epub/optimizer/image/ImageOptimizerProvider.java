package com.myhomelibcorp.epub.optimizer.image;

import com.myhomelibcorp.epub.optimizer.profile.ImageCodec;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/** Optional audited backend. Implementations may use a controlled external executable, never a shell command. */
public interface ImageOptimizerProvider {
    String id();
    boolean isAvailable();
    Set<ImageCodec> inputCodecs();
    Set<ImageCodec> outputCodecs();
    Optional<ImageOptimizationCandidate> optimize(Path sourceResource, ImageOptimizationRequest request, Path workspace) throws Exception;
}
