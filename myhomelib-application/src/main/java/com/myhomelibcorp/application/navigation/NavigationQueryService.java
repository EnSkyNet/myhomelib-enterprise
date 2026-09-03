package com.myhomelibcorp.application.navigation;

import com.myhomelibcorp.application.query.common.PageResult;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

public interface NavigationQueryService {

    default CompletableFuture<List<NavigationNodeDto>> load(NavigationMode mode) {
        return load(mode, null);
    }

    CompletableFuture<List<NavigationNodeDto>> load(NavigationMode mode, Character initial);

    CompletableFuture<PageResult<NavigationNodeDto>> loadAuthorsPage(Character initial, int limit, int offset);

    CompletableFuture<AuthorPage> loadAuthorsAfter(Character initial, int limit, AuthorCursor after);

    CompletableFuture<List<NavigationNodeDto>> searchAuthors(String query, int limit);

    CompletableFuture<Optional<Character>> findFirstAuthorInitial();

    CompletableFuture<Optional<Character>> findAuthorInitial(String authorId);

    record AuthorCursor(String lastName, String firstName, String middleName, String id) {
        public AuthorCursor {
            lastName = lastName == null ? "" : lastName;
            firstName = firstName == null ? "" : firstName;
            middleName = middleName == null ? "" : middleName;
            if (id == null || id.isBlank()) throw new IllegalArgumentException("author cursor id cannot be blank");
        }
    }

    record AuthorPage(List<NavigationNodeDto> content, OptionalLong totalElements, AuthorCursor nextCursor) {
        public AuthorPage {
            content = content == null ? List.of() : List.copyOf(content);
            totalElements = totalElements == null ? OptionalLong.empty() : totalElements;
        }

        public static AuthorPage empty() {
            return new AuthorPage(List.of(), OptionalLong.of(0), null);
        }
    }
}
