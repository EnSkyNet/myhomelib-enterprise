package com.myhomelibcorp.application.navigation;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface NavigationQueryService {

    default CompletableFuture<List<NavigationNodeDto>> load(NavigationMode mode) {
        return load(mode, null);
    }

    CompletableFuture<List<NavigationNodeDto>> load(NavigationMode mode, Character initial);

    CompletableFuture<Optional<Character>> findFirstAuthorInitial();

    CompletableFuture<Optional<Character>> findAuthorInitial(String authorId);
}
