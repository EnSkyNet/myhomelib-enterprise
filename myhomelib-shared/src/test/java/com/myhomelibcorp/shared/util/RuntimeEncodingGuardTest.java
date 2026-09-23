package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEncodingGuardTest {
    @Test
    void currentJava21RuntimeUsesUtf8DefaultCharset() {
        RuntimeEncodingGuard.Status status = RuntimeEncodingGuard.inspect();
        // Java 21 guarantees an UTF-8 default charset (JEP 400), but native.encoding remains
        // environment/locale dependent on Unix. The guard semantics themselves are covered by
        // the platform-specific tests below, so this ambient-runtime check must not require the
        // test runner to have an UTF-8 POSIX locale.
        assertThat(status.defaultCharset()).isEqualToIgnoringCase(StandardCharsets.UTF_8.name());
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
