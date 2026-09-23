package com.myhomelibcorp.ui.util;

/** Backward-compatible facade for user-safe localized exception text. */
public final class UiExceptionMessages {
    private UiExceptionMessages() {
    }

    public static String root(Throwable error) {
        return UiExceptionSupport.message(error);
    }

    public static String technicalRoot(Throwable error) {
        return UiExceptionSupport.technicalMessage(error);
    }
}
