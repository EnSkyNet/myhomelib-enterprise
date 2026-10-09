package com.myhomelibcorp.application.textprovider;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class TextProviderRegistrySupportTest {
    @Test
    void configuredProviderWinsDuplicateExtensionIdAndDefaultOrderIsDeterministic() {
        Provider configured = new Provider("same", true, 5);
        Provider extensionDuplicate = new Provider("same", true, 1);
        Provider preferred = new Provider("preferred", true, 0);

        List<Provider> merged = TextProviderRegistrySupport.mergeUniqueById(
                List.of(configured), List.of(extensionDuplicate, preferred), Provider::id);

        assertEquals(List.of(configured, preferred), merged);
        assertSame(configured, TextProviderRegistrySupport.select(
                merged, " same ", Provider::enabled, Comparator.comparingInt(Provider::rank), Provider::id));
        assertSame(preferred, TextProviderRegistrySupport.select(
                merged, null, Provider::enabled, Comparator.comparingInt(Provider::rank), Provider::id));
    }

    @Test
    void disabledProvidersCannotBeSelected() {
        Provider disabled = new Provider("disabled", false, 0);
        assertNull(TextProviderRegistrySupport.select(
                List.of(disabled), "disabled", Provider::enabled, Comparator.comparingInt(Provider::rank), Provider::id));
    }

    @Test
    void failuresHaveOneCanonicalClassification() {
        assertEquals(TextProviderErrorKind.TIMEOUT,
                TextProviderFailures.classify(new CompletionException(new java.util.concurrent.TimeoutException())));
        assertEquals(TextProviderErrorKind.CANCELLED,
                TextProviderFailures.classify(new java.util.concurrent.CancellationException()));
        assertEquals(TextProviderErrorKind.AUTHENTICATION,
                TextProviderFailures.classify(new TextProviderException(TextProviderErrorKind.AUTHENTICATION, "bad auth")));
        assertEquals(TextProviderErrorKind.FAILED, TextProviderFailures.classify(new IllegalStateException("boom")));
    }

    private record Provider(String id, boolean enabled, int rank) { }
}
