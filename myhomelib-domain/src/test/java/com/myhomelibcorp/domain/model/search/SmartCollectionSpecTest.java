package com.myhomelibcorp.domain.model.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartCollectionSpecTest {

    @Test
    void validatesTypedRulesAndNormalizesValues() {
        SmartCollectionRule rule = new SmartCollectionRule(
                SmartCollectionField.TITLE, SmartCollectionOperator.CONTAINS, "  Space   Opera  ", null);

        assertThat(rule.value()).isEqualTo("Space   Opera");
        assertThatThrownBy(() -> SmartCollectionRule.number(
                SmartCollectionField.RATING, SmartCollectionOperator.AT_LEAST, 6, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0..5");
        assertThatThrownBy(() -> new SmartCollectionRule(
                SmartCollectionField.LOCAL, SmartCollectionOperator.EQUALS, "true", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IS_TRUE/IS_FALSE");
    }

    @Test
    void rejectsInvalidBetweenAndUnsafeCollectionBounds() {
        assertThatThrownBy(() -> SmartCollectionRule.number(
                SmartCollectionField.YEAR, SmartCollectionOperator.BETWEEN, 2026, 2020))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower bound");

        SmartCollectionRule rule = SmartCollectionRule.text(
                SmartCollectionField.LANGUAGE, SmartCollectionOperator.EQUALS, "uk");
        assertThatThrownBy(() -> new SmartCollectionSpec(
                SmartCollectionMode.AND, List.of(rule), SmartCollectionSort.TITLE,
                SmartCollectionSortDirection.ASC, SmartCollectionSpec.MAX_RESULTS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResults");
    }

    @Test
    void suppliesStableDefaultsForConciseFactory() {
        SmartCollectionRule rule = SmartCollectionRule.text(
                SmartCollectionField.FORMAT, SmartCollectionOperator.EQUALS, "fb2");
        SmartCollectionSpec spec = SmartCollectionSpec.of(SmartCollectionMode.OR, List.of(rule));

        assertThat(spec.mode()).isEqualTo(SmartCollectionMode.OR);
        assertThat(spec.sort()).isEqualTo(SmartCollectionSort.TITLE);
        assertThat(spec.direction()).isEqualTo(SmartCollectionSortDirection.ASC);
        assertThat(spec.maxResults()).isEqualTo(10_000);
    }
}
