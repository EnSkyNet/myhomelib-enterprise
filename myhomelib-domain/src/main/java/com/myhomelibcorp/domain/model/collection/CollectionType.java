package com.myhomelibcorp.domain.model.collection;

/**
 * Типи колекцій в системі.
 */
public enum CollectionType {
    FB2_LOCAL(0, "Локальна FB2"),
    INPX_ARCHIVE(1, "INPX архів"),
    REMOTE(2, "Віддалена");

    private final int code;
    private final String displayName;

    CollectionType(int code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public int getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static CollectionType fromCode(int code) {
        for (CollectionType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return FB2_LOCAL;
    }

    @Override
    public String toString() {
        return displayName;
    }
}