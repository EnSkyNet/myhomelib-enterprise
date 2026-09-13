package com.myhomelibcorp.domain.model.search;

import java.util.EnumSet;
import java.util.Objects;

public record SmartCollectionRule(
        SmartCollectionField field,
        SmartCollectionOperator operator,
        String value,
        String secondValue
) {
    private static final EnumSet<SmartCollectionField> TEXT_FIELDS = EnumSet.of(
            SmartCollectionField.TITLE, SmartCollectionField.AUTHOR, SmartCollectionField.SERIES,
            SmartCollectionField.GENRE, SmartCollectionField.KEYWORD, SmartCollectionField.PUBLISHER);
    private static final EnumSet<SmartCollectionField> ENUM_FIELDS = EnumSet.of(
            SmartCollectionField.LANGUAGE, SmartCollectionField.FORMAT);
    private static final EnumSet<SmartCollectionField> NUMERIC_FIELDS = EnumSet.of(
            SmartCollectionField.YEAR, SmartCollectionField.PROGRESS, SmartCollectionField.RATING);

    public SmartCollectionRule {
        field = Objects.requireNonNull(field, "field");
        operator = Objects.requireNonNull(operator, "operator");
        value = normalize(value);
        secondValue = normalize(secondValue);
        validate(field, operator, value, secondValue);
    }

    public static SmartCollectionRule text(SmartCollectionField field, SmartCollectionOperator operator, String value) {
        return new SmartCollectionRule(field, operator, value, null);
    }

    public static SmartCollectionRule number(SmartCollectionField field, SmartCollectionOperator operator,
                                             Number value, Number secondValue) {
        return new SmartCollectionRule(field, operator,
                value == null ? null : value.toString(), secondValue == null ? null : secondValue.toString());
    }

    private static void validate(SmartCollectionField field, SmartCollectionOperator operator,
                                 String value, String secondValue) {
        if (field == SmartCollectionField.LOCAL) {
            if (operator != SmartCollectionOperator.IS_TRUE && operator != SmartCollectionOperator.IS_FALSE) {
                throw new IllegalArgumentException("LOCAL supports only IS_TRUE/IS_FALSE");
            }
            return;
        }
        if (TEXT_FIELDS.contains(field)) {
            require(operator == SmartCollectionOperator.CONTAINS || operator == SmartCollectionOperator.EQUALS
                    || operator == SmartCollectionOperator.NOT_EQUALS,
                    "Text field supports CONTAINS/EQUALS/NOT_EQUALS");
            requireValue(value);
            return;
        }
        if (ENUM_FIELDS.contains(field)) {
            require(operator == SmartCollectionOperator.EQUALS || operator == SmartCollectionOperator.NOT_EQUALS,
                    "Enum field supports EQUALS/NOT_EQUALS");
            requireValue(value);
            return;
        }
        if (NUMERIC_FIELDS.contains(field)) {
            require(operator == SmartCollectionOperator.EQUALS || operator == SmartCollectionOperator.NOT_EQUALS
                            || operator == SmartCollectionOperator.AT_LEAST || operator == SmartCollectionOperator.AT_MOST
                            || operator == SmartCollectionOperator.BETWEEN,
                    "Numeric field supports EQUALS/NOT_EQUALS/AT_LEAST/AT_MOST/BETWEEN");
            int first = parseInt(value, field);
            validateRange(field, first);
            if (operator == SmartCollectionOperator.BETWEEN) {
                int second = parseInt(secondValue, field);
                validateRange(field, second);
                require(first <= second, "BETWEEN lower bound must not exceed upper bound");
            }
        }
    }

    private static void validateRange(SmartCollectionField field, int value) {
        switch (field) {
            case YEAR -> require(value >= 1 && value <= 9999, "YEAR must be 1..9999");
            case PROGRESS -> require(value >= 0 && value <= 100, "PROGRESS must be 0..100");
            case RATING -> require(value >= 0 && value <= 5, "RATING must be 0..5");
            default -> { }
        }
    }

    private static int parseInt(String value, SmartCollectionField field) {
        requireValue(value);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " requires an integer value", e);
        }
    }

    private static void requireValue(String value) {
        require(value != null, "Rule value is required");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
