package com.myhomelibcorp.converter.toolchain;

import com.myhomelibcorp.application.conversion.BookConversionCapability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic v1 graph planner: direct route first, then one intermediate hop. */
public final class ConversionRoutePlanner {
    private final List<ConversionEngine> engines;

    public ConversionRoutePlanner(List<ConversionEngine> engines) {
        this.engines = List.copyOf(Objects.requireNonNull(engines, "engines"));
    }

    public Optional<ConversionRoute> plan(String sourceFormat, String targetFormat) {
        String source = BookConversionCapability.normalizeFormat(sourceFormat);
        String target = BookConversionCapability.normalizeFormat(targetFormat);
        if (source.isBlank() || target.isBlank() || source.equals(target)) return Optional.empty();

        List<ConversionStep> edges = availableEdges();
        List<ConversionRoute> candidates = new ArrayList<>();
        for (ConversionStep direct : edges) {
            if (direct.sourceFormat().equals(source) && direct.targetFormat().equals(target)) {
                candidates.add(new ConversionRoute(source, target, List.of(direct)));
            }
        }
        for (ConversionStep first : edges) {
            if (!first.sourceFormat().equals(source) || first.targetFormat().equals(target)) continue;
            for (ConversionStep second : edges) {
                if (!second.sourceFormat().equals(first.targetFormat()) || !second.targetFormat().equals(target)) continue;
                if (first.engineId() == second.engineId() && first.targetFormat().equals(second.sourceFormat())) {
                    // Same-engine chains are allowed, but still only when they are the best deterministic route.
                }
                candidates.add(new ConversionRoute(source, target, List.of(first, second)));
            }
        }
        return candidates.stream().min(routeComparator());
    }

    public List<ConversionStep> availableEdges() {
        return engines.stream()
                .filter(ConversionEngine::isAvailable)
                .flatMap(engine -> engine.capabilities().stream())
                .sorted(stepComparator())
                .toList();
    }

    private Comparator<ConversionRoute> routeComparator() {
        return Comparator.<ConversionRoute>comparingInt(route -> route.steps().size())
                .thenComparingLong(ConversionRoute::lossySteps)
                .thenComparing(Comparator.comparingInt(ConversionRoute::totalQuality).reversed())
                .thenComparingInt(ConversionRoute::totalCost)
                .thenComparing(route -> route.steps().stream().map(s -> s.engineId().name()).toList().toString());
    }

    private Comparator<ConversionStep> stepComparator() {
        return Comparator.comparing(ConversionStep::sourceFormat)
                .thenComparing(ConversionStep::targetFormat)
                .thenComparing(Comparator.comparingInt(ConversionStep::qualityRank).reversed())
                .thenComparingInt(ConversionStep::costRank)
                .thenComparing(step -> step.engineId().name());
    }
}
