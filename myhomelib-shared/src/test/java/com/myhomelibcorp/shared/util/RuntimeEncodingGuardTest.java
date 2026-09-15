package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEncodingGuardTest {
    @Test
    void currentJava21RuntimeUsesUtf8DefaultCharset() {
        RuntimeEncodingGuard.Status status = RuntimeEncodingGuard.inspect();
        assertThat(status.defaultCharset()).isEqualToIgnoringCase(StandardCharsets.UTF_8.name());
        assertThat(status.safeForUnicode()).isTrue();
    }

    @Test
    void windowsNativeCodePageDoesNotInvalidateUtf8JvmDefault() {
        RuntimeEncodingGuard.Status status = new RuntimeEncodingGuard.Status("UTF-8", "Cp1251", true, false);
        assertThat(status.safeForUnicode("Windows 11")).isTrue();
    }

    @Test
    void unixAsciiNativeEncodingIsUnsafeEvenWithUtf8DefaultCharset() {
        RuntimeEncodingGuard.Status status = new RuntimeEncodingGuard.Status("UTF-8", "ANSI_X3.4-1968", true, false);
        assertThat(status.safeForUnicode("Linux")).isFalse();
    }

    @Test
    void nonUtf8JvmDefaultIsReportedUnsafeRegardlessOfNativeEncoding() {
        RuntimeEncodingGuard.Status status = new RuntimeEncodingGuard.Status("US-ASCII", "UTF-8", false, true);
        assertThat(status.safeForUnicode("Linux")).isFalse();
    }
}
