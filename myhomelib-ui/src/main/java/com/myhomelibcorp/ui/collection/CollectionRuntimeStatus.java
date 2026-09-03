package com.myhomelibcorp.ui.collection;

/** Immutable state shown by the Collection workspace. */
public record CollectionRuntimeStatus(
        CollectionRuntimeState state,
        double fraction,
        String detail
) {
    public CollectionRuntimeStatus {
        state = state == null ? CollectionRuntimeState.READY : state;
        fraction = fraction < 0 ? -1.0 : Math.max(0.0, Math.min(1.0, fraction));
        detail = detail == null ? "" : detail;
    }

    public static CollectionRuntimeStatus ready() {
        return new CollectionRuntimeStatus(CollectionRuntimeState.READY, -1.0, "");
    }

    public String shortText() {
        return switch (state) {
            case CREATING -> progressText("◌ Створення");
            case READY -> "● Готова";
            case IMPORTING -> progressText("⟳ Імпорт");
            case INDEXING -> progressText("⟳ Індексація");
            case UPDATING -> progressText("⟳ Оновлення");
            case ERROR -> "⚠ Помилка";
            case DELETING -> progressText("◌ Видалення");
        };
    }

    public String detailsText() {
        if (state == CollectionRuntimeState.ERROR && !detail.isBlank()) return shortText() + " — " + detail;
        return shortText();
    }

    private String progressText(String label) {
        if (fraction < 0) return label;
        return label + " " + Math.round(fraction * 100.0) + "%";
    }
}
