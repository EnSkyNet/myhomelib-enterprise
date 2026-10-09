package com.myhomelibcorp.ui.collection;


import com.myhomelibcorp.ui.service.UiText;
/** Immutable state shown by the Collection workspace. */
public record CollectionRuntimeStatus(
        CollectionRuntimeState state,
        double fraction,
        String detail
) {
    public CollectionRuntimeStatus {
        state = state == null ? CollectionRuntimeState.READY : state;
        fraction = !Double.isFinite(fraction) || fraction < 0 ? -1.0 : Math.max(0.0, Math.min(1.0, fraction));
        detail = detail == null ? "" : detail;
    }

    public static CollectionRuntimeStatus ready() {
        return new CollectionRuntimeStatus(CollectionRuntimeState.READY, -1.0, "");
    }

    public String shortText() {
        return switch (state) {
            case CREATING -> progressText(UiText.text("ui.java.collection_runtime_status.4fc4c0a0e5"));
            case READY -> UiText.text("ui.java.collection_runtime_status.888f564abe");
            case IMPORTING -> progressText(UiText.text("ui.java.collection_runtime_status.fdb002f02e"));
            case INDEXING -> progressText(UiText.text("ui.java.collection_runtime_status.d2e13fedf7"));
            case UPDATING -> progressText(UiText.text("ui.java.collection_runtime_status.7b4147fe39"));
            case ERROR -> UiText.text("ui.java.collection_runtime_status.d189aeea0d");
            case DELETING -> progressText(UiText.text("ui.java.collection_runtime_status.222bddd632"));
        };
    }

    public String detailsText() {
        if (state == CollectionRuntimeState.ERROR && !detail.isBlank()) {
            return shortText() + " — " + UiText.text("common.error.details_in_log");
        }
        return shortText();
    }

    private String progressText(String label) {
        if (fraction < 0) return label;
        return label + " " + Math.round(fraction * 100.0) + "%";
    }
}
