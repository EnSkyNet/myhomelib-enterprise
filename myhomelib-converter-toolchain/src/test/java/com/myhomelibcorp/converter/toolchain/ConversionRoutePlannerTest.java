package com.myhomelibcorp.converter.toolchain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ConversionRoutePlannerTest {
    @Test
    void prefersDirectSpecializedRouteOverTwoStepBridge() {
        ConversionEngine fbc = engine(ConversionEngineId.FBC,
                step(ConversionEngineId.FBC, "fb2", "epub", 100, false, 1));
        ConversionEngine pandoc = engine(ConversionEngineId.PANDOC,
                step(ConversionEngineId.PANDOC, "fb2", "html", 90, false, 1),
                step(ConversionEngineId.PANDOC, "html", "epub", 90, false, 1));

        ConversionRoute route = new ConversionRoutePlanner(List.of(pandoc, fbc)).plan("FB2", "EPUB").orElseThrow();

        assertThat(route.steps()).singleElement().extracting(ConversionStep::engineId).isEqualTo(ConversionEngineId.FBC);
    }

    @Test
    void directRouteUsesQualityThenCostDeterministically() {
        ConversionEngine fbc = engine(ConversionEngineId.FBC,
                step(ConversionEngineId.FBC, "fb2", "epub", 100, false, 5));
        ConversionEngine pandoc = engine(ConversionEngineId.PANDOC,
                step(ConversionEngineId.PANDOC, "fb2", "epub", 60, false, 1));

        ConversionRoute route = new ConversionRoutePlanner(List.of(pandoc, fbc)).plan("fb2", "epub").orElseThrow();

        assertThat(route.steps().getFirst().engineId()).isEqualTo(ConversionEngineId.FBC);
    }

    private static ConversionStep step(ConversionEngineId id, String source, String target,
                                       int quality, boolean lossy, int cost) {
        return new ConversionStep(id, source, target, "." + target, quality, lossy, cost);
    }

    private static ConversionEngine engine(ConversionEngineId id, ConversionStep... steps) {
        return new ConversionEngine() {
            @Override public ConversionEngineId id() { return id; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<ConversionStep> capabilities() { return List.of(steps); }
            @Override public ConversionStepResult convert(ConversionStep step, Path source, Path target,
                                                          BooleanSupplier cancelled, long maxOutputBytes) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
