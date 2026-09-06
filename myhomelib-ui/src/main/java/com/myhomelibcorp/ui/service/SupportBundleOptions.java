package com.myhomelibcorp.ui.service;

/** User-selectable diagnostic bundle sections. Mandatory redacted environment/settings are always included. */
public record SupportBundleOptions(boolean includeLogs, boolean includeThreadDump, boolean includeReleaseDocuments) {
    public static SupportBundleOptions defaults() {
        return new SupportBundleOptions(true, true, true);
    }
}
