package com.myhomelibcorp.epub.optimizer.font;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.epub.optimizer.profile.ReadingSystemProfile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conservative publication-safety gate for optional font subset providers. */
@Component
public final class FontQualityGate {
    public Decision evaluate(FontResourceAnalysis source, FontSubsetResult candidate,
                             RequiredCodePointCollection required, ReadingSystemProfile readingSystem,
                             long minimumSavingsBytes, double minimumSavingsRatio) {
        List<String> reasons = new ArrayList<>();
        if (source == null || candidate == null || required == null || readingSystem == null) {
            reasons.add("missing-analysis");
            return new Decision(false, reasons);
        }
        if (source.encrypted()) reasons.add("encrypted-font");
        if (!source.variableFontKnown()) reasons.add("variable-font-state-unknown");
        else if (source.variableFont()) reasons.add("variable-font-subsetting-not-proven-safe");
        if (!required.complete()) reasons.add("required-code-point-set-incomplete");
        if (required.codePoints().isEmpty()) reasons.add("required-code-point-set-empty");
        if (!candidate.glyphCoverageVerified()) reasons.add("glyph-coverage-not-verified");
        if (candidate.file() == null) reasons.add("candidate-file-missing");
        else {
            try {
                var safeCandidate = EpubPathSafety.requireSafeRegularFile(candidate.file(), "Font optimization candidate");
                if (Files.size(safeCandidate) != candidate.byteSize()) reasons.add("candidate-size-metadata-mismatch");
            } catch (Exception ignored) { reasons.add("candidate-size-unreadable"); }
        }
        if (candidate.byteSize() <= 0 || candidate.byteSize() >= source.byteSize()) reasons.add("candidate-not-smaller");
        long saved = Math.max(0L, source.byteSize() - candidate.byteSize());
        if (saved < Math.max(0L, minimumSavingsBytes)) reasons.add("minimum-byte-saving-not-met");
        double ratio = source.byteSize() <= 0 ? 0.0 : saved / (double) source.byteSize();
        if (ratio < Math.max(0.0, minimumSavingsRatio)) reasons.add("minimum-saving-ratio-not-met");
        String media = candidate.mediaType().toLowerCase(Locale.ROOT);
        String sourceMedia = source.mediaType().toLowerCase(Locale.ROOT);
        if (!media.isBlank() && !sourceMedia.isBlank() && !media.equals(sourceMedia)) {
            reasons.add("font-media-type-change-not-supported-in-v1");
        }
        if (media.contains("woff2") && !readingSystem.supportsWoff2()) reasons.add("woff2-not-supported-by-reading-profile");
        return new Decision(reasons.isEmpty(), reasons);
    }

    public record Decision(boolean accepted, List<String> reasons) {
        public Decision { reasons = List.copyOf(reasons == null ? List.of() : reasons); }
    }
}
