package com.myhomelibcorp.epub.optimizer.image;

import com.myhomelibcorp.epub.optimizer.profile.ImageCodec;
import com.myhomelibcorp.epub.optimizer.profile.StandardReadingSystemProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

class ImageQualityGateTest {
    @TempDir Path tempDir;

    @Test
    void rejectsUpscaleEvenWhenCandidateIsSmaller() throws Exception {
        Path candidateFile = tempDir.resolve("candidate.png");
        Files.write(candidateFile, new byte[100]);
        ImageResourceAnalysis source = new ImageResourceAnalysis("i.png", ImageCodec.PNG,
                100, 100, true, true, true, false, true, 8, 1000,
                false, false, 0, ImageRole.CONTENT, 1);
        ImageOptimizationCandidate candidate = new ImageOptimizationCandidate(candidateFile, ImageCodec.PNG,
                200, 200, true, false, 100, true, OptionalDouble.empty());

        ImageQualityGate.Decision decision = new ImageQualityGate().evaluate(source, candidate,
                StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT, 1, 0.01, 1.0);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.reasons()).contains("upscale-forbidden");
    }

    @Test
    void rejectsLossyCandidateWithoutPerceptualProof() throws Exception {
        Path candidateFile = tempDir.resolve("candidate.jpg");
        Files.write(candidateFile, new byte[100]);
        ImageResourceAnalysis source = new ImageResourceAnalysis("i.jpg", ImageCodec.JPEG,
                100, 100, true, false, true, false, true, 8, 1000,
                false, false, 0, ImageRole.CONTENT, 1);
        ImageOptimizationCandidate candidate = new ImageOptimizationCandidate(candidateFile, ImageCodec.JPEG,
                100, 100, false, false, 100, false, OptionalDouble.empty());

        ImageQualityGate.Decision decision = new ImageQualityGate().evaluate(source, candidate,
                StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT, 1, 0.01, 0.99);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.reasons()).contains("lossy-candidate-without-perceptual-score");
    }
    @Test
    void rejectsCandidateReachedThroughSymlinkedParentWhenPlatformAllowsSymlinks() throws Exception {
        Path real = Files.createDirectory(tempDir.resolve("real"));
        Path candidateFile = Files.write(real.resolve("candidate.png"), new byte[100]);
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }
        ImageResourceAnalysis source = new ImageResourceAnalysis("i.png", ImageCodec.PNG,
                100, 100, true, true, true, false, true, 8, 1000,
                false, false, 0, ImageRole.CONTENT, 1);
        ImageOptimizationCandidate candidate = new ImageOptimizationCandidate(link.resolve(candidateFile.getFileName()), ImageCodec.PNG,
                100, 100, true, false, 100, true, OptionalDouble.empty());

        ImageQualityGate.Decision decision = new ImageQualityGate().evaluate(source, candidate,
                StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT, 1, 0.01, 1.0);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.reasons()).contains("candidate-file-missing-or-unsafe");
    }

}
