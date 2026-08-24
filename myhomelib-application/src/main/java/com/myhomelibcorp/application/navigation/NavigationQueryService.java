package com.myhomelibcorp.application.navigation;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Application query boundary used by desktop navigation.
 * Implementations own catalogue access, ordering and stable identifiers;
 * JavaFX is responsible only for presentation/filtering of returned nodes.
 */
public interface NavigationQueryService {
    CompletableFuture<List<NavigationNodeDto>> load(NavigationMode mode);
}
