package com.myhomelibcorp.application.ai;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable capability declaration used before a provider is invoked. */
public record AiProviderCapabilities(
        Set<AiOperation> operations,
        boolean networkRequired,
        Set<String> requiredSecrets
) {
    private static final Pattern SECRET_NAME = Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*");

    public AiProviderCapabilities {
        operations = Set.copyOf(Objects.requireNonNull(operations, "operations"));
        if (operations.isEmpty()) throw new IllegalArgumentException("at least one AI operation is required");
        requiredSecrets = Set.copyOf(Objects.requireNonNull(requiredSecrets, "requiredSecrets"));
        for (String name : requiredSecrets) {
            if (name == null || !SECRET_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid AI secret name: " + name);
            }
        }
    }

    public static AiProviderCapabilities local(Set<AiOperation> operations) {
        return new AiProviderCapabilities(operations, false, Set.of());
    }
}
