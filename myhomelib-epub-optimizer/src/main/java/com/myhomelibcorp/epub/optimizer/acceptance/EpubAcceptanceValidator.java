package com.myhomelibcorp.epub.optimizer.acceptance;

import java.nio.file.Path;

/** Optional deep validator executed after the mandatory structural/semantic checks. */
public interface EpubAcceptanceValidator {
    Kind kind();
    String id();
    Result validate(Path epub);

    enum Kind { EPUBCHECK, READER_REOPEN }

    record Result(boolean available, boolean passed, String details) {
        public Result { details = details == null ? "" : details; }
        public static Result unavailable(String details) { return new Result(false, true, details); }
        public static Result passed(String details) { return new Result(true, true, details); }
        public static Result failed(String details) { return new Result(true, false, details); }
    }
}
