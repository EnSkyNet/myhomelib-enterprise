package com.myhomelibcorp.application.port.out.exchange;

import java.nio.file.Path;

/** Export/import user-owned state independently from collection metadata. */
public interface UserDataExchangePort {
    record Result(int booksUpdated, int groupsUpdated, int bookmarksUpdated, int progressUpdated) {}
    void exportTo(Path file);
    Result importFrom(Path file);
}
