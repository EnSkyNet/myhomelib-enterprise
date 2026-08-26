package com.myhomelibcorp.application.port.out.download;

import java.util.List;

/** A resolved network update: zero or more packages in the order they must be imported. */
public record RemoteCatalogUpdatePlan(
        List<RemoteCatalogPackage> packages,
        String latestVersion
) {
    public RemoteCatalogUpdatePlan {
        packages = packages == null ? List.of() : List.copyOf(packages);
    }

    public boolean upToDate() {
        return packages.isEmpty();
    }
}
