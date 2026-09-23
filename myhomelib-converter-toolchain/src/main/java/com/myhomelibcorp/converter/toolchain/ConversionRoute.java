package com.myhomelibcorp.converter.toolchain;

import java.util.List;
import java.util.Objects;

public record ConversionRoute(String sourceFormat, String targetFormat, List<ConversionStep> steps) {
    public ConversionRoute {
        Objects.requireNonNull(sourceFormat, "sourceFormat");
        Objects.requireNonNull(targetFormat, "targetFormat");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) throw new IllegalArgumentException("Route must contain at least one conversion step");
        if (steps.size() > 2) throw new IllegalArgumentException("Toolchain v1 supports at most two conversion steps");
    }

    public int totalQuality() { return steps.stream().mapToInt(ConversionStep::qualityRank).sum(); }
    public int totalCost() { return steps.stream().mapToInt(ConversionStep::costRank).sum(); }
    public long lossySteps() { return steps.stream().filter(ConversionStep::lossy).count(); }
}
