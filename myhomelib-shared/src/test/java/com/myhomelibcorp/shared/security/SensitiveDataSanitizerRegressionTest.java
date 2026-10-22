package com.myhomelibcorp.shared.security;

import org.junit.jupiter.api.Test;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataSanitizerRegressionTest {
    private static String uri(String raw) {
        return SensitiveDataSanitizer.sanitizeUri(URI.create(raw));
    }

    private static void redacts(String raw, String... forbidden) {
        String safe = uri(raw);
        assertThat(safe).contains("%3Credacted%3E");
        for (String secret : forbidden) assertThat(safe).doesNotContain(secret);
    }

    @Test void preservesEscapedPathAndUnrelatedQuery() {
        assertThat(uri("https://example.org/folder%20name/%D1%96?lang=uk"))
                .isEqualTo("https://example.org/folder%20name/%D1%96?lang=uk");
    }

    @Test void preservesUnicodeAsSingleAsciiEncoding() {
        assertThat(uri("https://example.org/українська?lang=uk"))
                .isEqualTo("https://example.org/%D1%83%D0%BA%D1%80%D0%B0%D1%97%D0%BD%D1%81%D1%8C%D0%BA%D0%B0?lang=uk");
    }

    @Test void stripsCredentialsAndFragment() {
        assertThat(uri("https://alice:PASSCODE@example.org/book#PRIVATE_FRAGMENT"))
                .isEqualTo("https://example.org/book");
    }

    @Test void keepsIpv6LiteralAndPort() {
        assertThat(uri("https://[::1]:8080/a%20b?lang=uk"))
                .isEqualTo("https://[::1]:8080/a%20b?lang=uk");
    }

    @Test void handlesRelativeUrisWithoutLosingEscapes() {
        assertThat(uri("/relative%20book?lang=uk#secret"))
                .isEqualTo("/relative%20book?lang=uk");
    }

    @Test void opaqueUrisNeverDiscloseSchemeSpecificData() {
        assertThat(uri("mailto:user:SECRETCODE@example.org")).isEqualTo("mailto:<redacted>");
    }

    @Test void preservesEmptyAndFlagQueryParameters() {
        assertThat(uri("https://example.org/a?flag&lang=uk"))
                .isEqualTo("https://example.org/a?flag&lang=uk");
        assertThat(uri("https://example.org/a?x=1&&y=2"))
                .isEqualTo("https://example.org/a?x=1&&y=2");
        assertThat(uri("https://example.org/a?")).isEqualTo("https://example.org/a?");
    }

    @Test void redactsPlainSensitiveParameters() {
        redacts("https://example.org/a?api_key=QUA1", "QUA1");
        redacts("https://example.org/a?password=QUA2", "QUA2");
    }

    @Test void redactsPercentEncodedQueryKeys() {
        redacts("https://example.org/a?api%5Fkey=QUA3", "QUA3");
        redacts("https://example.org/a?%61pi_key=QUA4", "QUA4");
        redacts("https://example.org/a?api%2Ekey=QUA5", "QUA5");
    }

    @Test void redactsRepeatedPercentEncoding() {
        redacts("https://example.org/a?%2561pi_key=QUA6", "QUA6");
        redacts("https://example.org/a?api%25255Fkey=QUA7", "QUA7");
    }

    @Test void failsClosedOnInvalidDecodedKey() {
        redacts("https://example.org/a?%25XX=QUA8", "QUA8");
    }

    @Test void redactsOAuthTokenAndClientSecretFamilies() {
        redacts("https://example.org/a?client_secret=QUA9&id_token=QUA10", "QUA9", "QUA10");
        redacts("https://example.org/a?refresh_token=QUA11", "QUA11");
    }

    @Test void redactsVendorSpecificSensitiveSuffixes() {
        redacts("https://example.org/a?my_custom_token=QUA12", "QUA12");
        redacts("https://example.org/a?service_secret=QUA13", "QUA13");
        redacts("https://example.org/a?service_password=QUA14", "QUA14");
    }

    @Test void treatsCaseDashAndDotAsEquivalentForKeyMatching() {
        redacts("https://example.org/a?API-KEY=QUA15", "QUA15");
        redacts("https://example.org/a?api.key=QUA16", "QUA16");
    }

    @Test void treatsSemicolonsAsPotentialQuerySeparators() {
        redacts("https://example.org/a?auth=QUA17;lang=uk", "QUA17");
        redacts("https://example.org/a?lang=uk;token=QUA18", "QUA18");
    }

    @Test void redactsRepeatedKeysAndPreservesOtherValues() {
        String safe = uri("https://example.org/a?token=QUA19&lang=uk&token=QUA20");
        assertThat(safe).doesNotContain("QUA19", "QUA20")
                .contains("lang=uk").contains("token=%3Credacted%3E");
    }

    @Test void stripsBasicBearerAndAuthorizationTokensInText() {
        assertThat(SensitiveDataSanitizer.sanitizeText("Authorization: Bearer ZZZ111"))
                .doesNotContain("ZZZ111");
        assertThat(SensitiveDataSanitizer.sanitizeText("Basic ZZZ222"))
                .doesNotContain("ZZZ222");
        assertThat(SensitiveDataSanitizer.sanitizeText("authorization=ZZZ333"))
                .doesNotContain("ZZZ333");
    }

    @Test void redactsQuotedAndUnquotedKeyValueSecretsInText() {
        assertThat(SensitiveDataSanitizer.sanitizeText("password=\"SPACE SECRET\""))
                .doesNotContain("SPACE SECRET");
        assertThat(SensitiveDataSanitizer.sanitizeText("token='QUOTED SECRET'"))
                .doesNotContain("QUOTED SECRET");
        assertThat(SensitiveDataSanitizer.sanitizeText("client_secret=ZZZ444; session_token:ZZZ555"))
                .doesNotContain("ZZZ444", "ZZZ555");
        assertThat(SensitiveDataSanitizer.sanitizeText("access-token=ZZZ666"))
                .doesNotContain("ZZZ666");
    }

    @Test void nullAndBlankInputsStayWellDefined() {
        assertThat(SensitiveDataSanitizer.sanitizeUri(null)).isEmpty();
        assertThat(SensitiveDataSanitizer.sanitizeText(null)).isNull();
        assertThat(SensitiveDataSanitizer.sanitizeText(" ")).isEqualTo(" ");
    }

    @Test void sanitizedNetworkUrlsRemainParseableAndDoNotDoubleEncode() {
        String sanitized = uri("https://u:p@example.org/a%20b?api%5Fkey=QUA21&lang=uk#gone");
        assertThat(sanitized).isEqualTo("https://example.org/a%20b?api%5Fkey=%3Credacted%3E&lang=uk");
        assertThat(URI.create(sanitized).getRawPath()).isEqualTo("/a%20b");
    }
}
