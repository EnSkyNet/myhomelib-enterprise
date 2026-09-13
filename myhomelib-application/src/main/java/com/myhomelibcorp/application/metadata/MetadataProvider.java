package com.myhomelibcorp.application.metadata;

import java.util.List;

/** Vendor-neutral application SPI for online book metadata providers. */
public interface MetadataProvider {
    String id();

    String displayName();

    /** Allows a configured adapter to be disabled without leaking vendor-specific settings to callers. */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Performs one bounded lookup. Implementations must honor the common context deadline/cancellation contract,
     * return normalized candidates and map remote failures to MetadataProviderException.
     */
    List<MetadataCandidate> search(MetadataQuery query, MetadataRequestContext context) throws MetadataProviderException;
}
