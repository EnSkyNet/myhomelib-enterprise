package com.myhomelibcorp.application.navigation;

import com.myhomelibcorp.application.query.common.PageResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface NavigationQueryService {

    default CompletableFuture<List<NavigationNodeDto>> load(NavigationMode mode) {
        return load(mode, null);
    }

    CompletableFuture<List<NavigationNodeDto>> load(NavigationMode mode, Character initial);

    CompletableFuture<PageResult<NavigationNodeDto>> loadAuthorsPage(Character initial, int limit, int offset);

    CompletableFuture<List<NavigationNodeDto>> searchAuthors(String query, int limit);

    CompletableFuture<Optional<Character>> findFirstAuthorInitial();

    CompletableFuture<Optional<Character>> findAuthorInitial(String authorId);
}
