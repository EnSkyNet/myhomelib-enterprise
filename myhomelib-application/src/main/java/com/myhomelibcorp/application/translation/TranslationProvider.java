package com.myhomelibcorp.application.translation;

import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;

/** Translation SPI. UI must invoke it only from an explicit user action. */
public interface TranslationProvider {
    String id();

    String displayName();

    default boolean isEnabled() {
        return true;
    }

    /** True when selected text leaves the local machine. */
    default boolean isRemote() {
        return true;
    }

    TranslationResult translate(TranslationQuery query, TextProviderRequestContext context)
            throws TextProviderException;
}
