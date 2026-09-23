package com.myhomelibcorp.converter.toolchain.validation;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class ConversionOutputValidators {
    private final List<ConversionOutputValidator> validators;

    public ConversionOutputValidators(List<ConversionOutputValidator> validators) {
        this.validators = validators.stream()
                .sorted(Comparator.comparing(v -> v instanceof BasicOutputValidator ? 1 : 0))
                .toList();
    }

    public ConversionOutputValidator forFormat(String format) {
        return validators.stream().filter(v -> v.supports(format)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No output validator registered for " + format));
    }
}
