package com.myhomelibcorp.infrastructure.download;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialTransportPolicyTest {
    @Test
    void blocksCredentialsOnPlainHttpIncludingLoopback() {
        assertThatThrownBy(() -> CredentialTransportPolicy.requireHttpsWhenCredentialsPresent(
                URI.create("http://127.0.0.1:8080/books"), "reader"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void allowsCredentialsOnHttps() {
        assertThatCode(() -> CredentialTransportPolicy.requireHttpsWhenCredentialsPresent(
                URI.create("https://library.example/books"), "reader"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsHttpWhenNoCredentialsAreConfigured() {
        assertThatCode(() -> CredentialTransportPolicy.requireHttpsWhenCredentialsPresent(
                URI.create("http://library.example/books"), ""))
                .doesNotThrowAnyException();
    }
}
