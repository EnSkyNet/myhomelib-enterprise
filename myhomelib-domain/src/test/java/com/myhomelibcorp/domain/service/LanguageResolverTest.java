package com.myhomelibcorp.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageResolverTest {
    @Test
    void normalizesAliasesAndUsesUndForUnknownValues() {
        assertThat(LanguageResolver.resolveValue("ua")).isEqualTo("uk");
        assertThat(LanguageResolver.resolveValue("ukr")).isEqualTo("uk");
        assertThat(LanguageResolver.resolveValue("rus")).isEqualTo("ru");
        assertThat(LanguageResolver.resolveValue("Russian")).isEqualTo("ru");
        assertThat(LanguageResolver.resolveValue("eng")).isEqualTo("en");
        assertThat(LanguageResolver.resolveValue("English")).isEqualTo("en");
        assertThat(LanguageResolver.resolveValue("EN_us")).isEqualTo("en-US");
        assertThat(LanguageResolver.resolveValue("not-a-language-value")).isEqualTo("und");
        assertThat(LanguageResolver.resolveValue(" ")).isEqualTo("und");
    }
}
