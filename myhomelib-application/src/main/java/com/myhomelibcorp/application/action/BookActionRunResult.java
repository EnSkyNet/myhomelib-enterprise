package com.myhomelibcorp.application.action;

import java.util.List;

public record BookActionRunResult(boolean success, int startedCommands, List<String> errors) {
    public BookActionRunResult { errors = errors == null ? List.of() : List.copyOf(errors); }
    public static BookActionRunResult success(int count) { return new BookActionRunResult(true, count, List.of()); }
    public static BookActionRunResult failure(int count, String error) {
        return new BookActionRunResult(false, count, List.of(error == null ? "Unknown error" : error));
    }
}
