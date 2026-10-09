package com.myhomelibcorp.application.textprovider;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Shared deterministic provider merge/selection rules for explicit text-provider features. */
public final class TextProviderRegistrySupport {
    private TextProviderRegistrySupport() { }

    public static <P> List<P> mergeUniqueById(
            List<P> configured,
            List<P> extensions,
            Function<P, String> id) {
        Objects.requireNonNull(id, "id");
        LinkedHashMap<String, P> result = new LinkedHashMap<>();
        addAll(result, configured, id);
        addAll(result, extensions, id);
        return List.copyOf(result.values());
    }

    public static <P> P select(
            List<P> providers,
            String requestedId,
            Predicate<P> enabled,
            Comparator<P> defaultOrder,
            Function<P, String> id) {
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(defaultOrder, "defaultOrder");
        Objects.requireNonNull(id, "id");

        String requested = normalizeId(requestedId);
        if (!requested.isEmpty()) {
            return providers.stream()
                    .filter(Objects::nonNull)
                    .filter(enabled)
                    .filter(provider -> requested.equals(id.apply(provider)))
                    .findFirst()
                    .orElse(null);
        }
        return providers.stream()
                .filter(Objects::nonNull)
                .filter(enabled)
                .min(defaultOrder)
                .orElse(null);
    }

    public static String normalizeId(String value) {
        return value == null ? "" : value.trim();
    }

    private static <P> void addAll(
            LinkedHashMap<String, P> result,
            List<P> providers,
            Function<P, String> id) {
        if (providers == null) return;
        for (P provider : providers) {
            if (provider != null) result.putIfAbsent(id.apply(provider), provider);
        }
    }
}
