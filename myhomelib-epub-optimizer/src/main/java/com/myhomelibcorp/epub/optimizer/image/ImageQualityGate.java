package com.myhomelibcorp.epub.optimizer.image;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.epub.optimizer.profile.ReadingSystemProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class ImageQualityGate {
    public Decision evaluate(ImageResourceAnalysis source, ImageOptimizationCandidate candidate,
                             ReadingSystemProfile readingSystem, long minimumSavingsBytes,
                             double minimumSavingsRatio, double perceptualFloor) {
        List<String> reasons = new ArrayList<>();
        if (source == null || candidate == null || readingSystem == null) reasons.add("missing-analysis");
        if (!reasons.isEmpty()) return new Decision(false, reasons);
        if (!readingSystem.supportedImageCodecs().contains(candidate.codec())) reasons.add("codec-not-supported-by-reading-profile");
        if (candidate.file() == null) {
            reasons.add("candidate-file-missing");
        } else {
            try {
                var safeCandidate = EpubPathSafety.requireSafeRegularFile(candidate.file(), "EPUB image optimization candidate");
                if (java.nio.file.Files.size(safeCandidate) != candidate.byteSize()) reasons.add("candidate-size-metadata-mismatch");
            } catch (Exception ignored) {
                reasons.add("candidate-file-missing-or-unsafe");
            }
        }
        if (candidate.byteSize() <= 0 || candidate.byteSize() >= source.byteSize()) reasons.add("candidate-not-smaller");
        long saved = Math.max(0L, source.byteSize() - candidate.byteSize());
        if (saved < Math.max(0L, minimumSavingsBytes)) reasons.add("minimum-byte-saving-not-met");
        double ratio = source.byteSize() <= 0 ? 0.0 : saved / (double) source.byteSize();
        if (ratio < Math.max(0.0, minimumSavingsRatio)) reasons.add("minimum-saving-ratio-not-met");
        if (source.dimensionsKnown()) {
            if (candidate.width() <= 0 || candidate.height() <= 0) reasons.add("candidate-dimensions-unknown");
            if (candidate.width() > source.width() || candidate.height() > source.height()) reasons.add("upscale-forbidden");
        }
        if (!source.alphaKnown() && (source.codec() == com.myhomelibcorp.epub.optimizer.profile.ImageCodec.PNG
                || source.codec() == com.myhomelibcorp.epub.optimizer.profile.ImageCodec.GIF
                || source.codec() == com.myhomelibcorp.epub.optimizer.profile.ImageCodec.WEBP)) reasons.add("source-alpha-state-unknown");
        if (source.alphaKnown() && source.alpha() && !candidate.alpha()) reasons.add("alpha-lost");
        if (!source.animationKnown() && (source.codec() == com.myhomelibcorp.epub.optimizer.profile.ImageCodec.GIF
                || source.codec() == com.myhomelibcorp.epub.optimizer.profile.ImageCodec.WEBP)) reasons.add("source-animation-state-unknown");
        if (source.animationKnown() && source.animated() != candidate.animated()) reasons.add("animation-semantics-changed");
        if (!candidate.lossless()) {
            if (candidate.perceptualScore().isEmpty()) reasons.add("lossy-candidate-without-perceptual-score");
            else if (candidate.perceptualScore().getAsDouble() < perceptualFloor) reasons.add("perceptual-quality-floor-not-met");
        }
        return new Decision(reasons.isEmpty(), List.copyOf(reasons));
    }

    public record Decision(boolean accepted, List<String> reasons) {
        public Decision { reasons = List.copyOf(reasons == null ? List.of() : reasons); }
    }
}
