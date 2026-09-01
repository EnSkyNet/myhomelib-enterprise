package com.myhomelibcorp.application.port.out.download;

import java.util.List;

/** A resolved network update: zero or more packages in the order they must be imported. */
public interface RemoteCatalogUpdatePlan {
    List<RemoteCatalogPackage> packages();
    String latestVersion();

    static RemoteCatalogUpdatePlan of(List<RemoteCatalogPackage> packages, String latestVersion) {
        return new Impl(packages, latestVersion);
    }

    default boolean upToDate() {
        return packages().isEmpty();
    }

    /** Internal implementation - not part of the public API. */
    record Impl(
            List<RemoteCatalogPackage> packages,
            String latestVersion
    ) implements RemoteCatalogUpdatePlan {
        // Public compact constructor
        public Impl {
            packages = packages == null ? List.of() : List.copyOf(packages);
        }
    }
}