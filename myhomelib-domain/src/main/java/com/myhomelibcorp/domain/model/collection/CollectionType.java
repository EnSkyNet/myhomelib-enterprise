package com.myhomelibcorp.domain.model.collection;

/**
 * Collection modes exposed by the modern MyHomeLib wizard.
 *
 * <p>Codes 0..2 are kept compatible with earlier Java builds.  The extra
 * generic modes model the original MyHomeLib distinction between FB2 and
 * non-FB2 collections without changing existing databases.</p>
 */
public enum CollectionType {
    FB2_LOCAL(0, "Локальна FB2", false, true, false),
    INPX_ARCHIVE(1, "Підключена INPX / зовнішня бібліотека", false, true, true),
    REMOTE(2, "Online FB2 / INPX", true, true, true),
    GENERIC_LOCAL(3, "Локальна (будь-які формати)", false, false, false),
    GENERIC_REMOTE(4, "Online (будь-які формати)", true, false, true);

    private final int code;
    private final String displayName;
    private final boolean remote;
    private final boolean fb2Collection;
    private final boolean external;

    CollectionType(int code, String displayName, boolean remote, boolean fb2Collection, boolean external) {
        this.code = code;
        this.displayName = displayName;
        this.remote = remote;
        this.fb2Collection = fb2Collection;
        this.external = external;
    }

    public int getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public boolean isRemote() { return remote; }
    public boolean isFb2Collection() { return fb2Collection; }
    public boolean isExternal() { return external; }

    /**
     * Локальний source-файл обов'язковий лише для явно підключеної INPX-колекції.
     * Online-колекції можуть стартувати лише з URL і отримати INPX пізніше з мережі.
     */
    public boolean requiresSource() { return this == INPX_ARCHIVE; }
    public boolean requiresUrl() { return remote; }

    public static CollectionType fromCode(int code) {
        for (CollectionType type : values()) if (type.code == code) return type;
        return FB2_LOCAL;
    }

    @Override public String toString() { return displayName; }
}
