package com.myhomelibcorp.application.dictionary;

import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;

import java.util.List;

/** Provider-neutral dictionary SPI. Providers are invoked only after an explicit reader action. */
public interface DictionaryProvider {
    String id();

    String displayName();

    default boolean isEnabled() {
        return true;
    }

    /** Offline providers are preferred when no provider id was explicitly selected. */
    boolean isOffline();

    List<DictionaryEntry> lookup(DictionaryQuery query, TextProviderRequestContext context)
            throws TextProviderException;
}
