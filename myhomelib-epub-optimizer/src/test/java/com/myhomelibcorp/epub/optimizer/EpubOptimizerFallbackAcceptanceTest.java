package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.acceptance.EpubAcceptanceValidator;
import com.myhomelibcorp.epub.optimizer.graph.EpubExactDuplicateDeduplicator;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraph;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraphAnalyzer;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourcePruner;
import com.myhomelibcorp.epub.optimizer.graph.EpubSafeMarkupNormalizer;
import com.myhomelibcorp.epub.optimizer.packaging.EpubPackager;
import com.myhomelibcorp.epub.optimizer.profile.StandardReadingSystemProfile;
import com.myhomelibcorp.epub.optimizer.semantic.EpubSemanticVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EpubOptimizerFallbackAcceptanceTest {
    @TempDir Path temp;

    @Test
    void standardModeRevalidatesSourceWhenLargerCandidateIsDiscarded() throws Exception {
        Path source = temp.resolve("source.epub");
        Path target = temp.resolve("target.epub");
        Files.writeString(source, "SOURCE");

        EpubStructuralValidator structuralValidator = mock(EpubStructuralValidator.class);
        EpubResourceGraphAnalyzer graphAnalyzer = mock(EpubResourceGraphAnalyzer.class);
        EpubPackager packager = mock(EpubPackager.class);
        EpubExactDuplicateDeduplicator deduplicator = mock(EpubExactDuplicateDeduplicator.class);
        EpubResourcePruner pruner = mock(EpubResourcePruner.class);
        EpubSafeMarkupNormalizer normalizer = mock(EpubSafeMarkupNormalizer.class);
        EpubSemanticVerifier semanticVerifier = mock(EpubSemanticVerifier.class);
        EpubPackageVersionInspector versionInspector = mock(EpubPackageVersionInspector.class);
        EpubBinaryResourceOptimizer binaryOptimizer = mock(EpubBinaryResourceOptimizer.class);

        EpubResourceGraph graph = new EpubResourceGraph("OEBPS/content.opf", Map.of(), List.of(), Set.of(), List.of());
        when(graphAnalyzer.analyze(any(Path.class))).thenReturn(graph);
        when(versionInspector.packageVersion(any(Path.class))).thenReturn("3.3");
        when(normalizer.normalize(any(Path.class), any(Path.class), any(EpubResourceGraph.class)))
                .thenReturn(new EpubSafeMarkupNormalizer.Result(0, 0, 0));
        when(binaryOptimizer.optimize(any(Path.class), any(Path.class), any(EpubResourceGraph.class),
                any(EpubOptimizationProfile.class), any(), any(Path.class)))
                .thenAnswer(invocation -> new EpubBinaryResourceOptimizer.Result(
                        invocation.getArgument(0), false, 0, 0, List.of()));
        when(semanticVerifier.verify(any(Path.class), any(Path.class)))
                .thenReturn(new EpubSemanticVerifier.Verification(true, List.of()));
        doAnswer(invocation -> {
            Path candidate = invocation.getArgument(1);
            Files.write(candidate, new byte[512]);
            return null;
        }).when(packager).repack(any(Path.class), any(Path.class), any());

        EpubAcceptanceValidator reader = new EpubAcceptanceValidator() {
            @Override public Kind kind() { return Kind.READER_REOPEN; }
            @Override public String id() { return "test-reader"; }
            @Override public Result validate(Path epub) {
                try {
                    return java.util.Arrays.equals(Files.readAllBytes(epub), "SOURCE".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            ? Result.failed("source cannot be reopened")
                            : Result.passed("candidate reopened");
                } catch (java.io.IOException failure) {
                    return Result.failed("cannot read acceptance artifact: " + failure.getMessage());
                }
            }
        };

        EpubOptimizer optimizer = new EpubOptimizer(structuralValidator, graphAnalyzer, packager, deduplicator,
                pruner, normalizer, semanticVerifier, versionInspector, binaryOptimizer, List.of(reader));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> optimizer.optimize(source, target, EpubOptimizationProfile.BALANCED_EPUB33,
                        StandardReadingSystemProfile.GENERIC_EPUB33_COMPAT, EpubAcceptanceMode.STANDARD));

        assertTrue(failure.getMessage().contains("source fallback"));
        assertTrue(failure.getMessage().contains("source cannot be reopened"));
        assertFalse(Files.exists(target), "A fallback source that fails acceptance must not be published");
    }
}
