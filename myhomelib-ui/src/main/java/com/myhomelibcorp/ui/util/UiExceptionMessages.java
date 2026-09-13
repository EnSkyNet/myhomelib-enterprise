package com.myhomelibcorp.ui.util;

public final class UiExceptionMessages {
    private UiExceptionMessages() {
    }

    public static String root(Throwable error) {
        if (error == null) return "Невідома помилка";
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }
}
