package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.converter.toolchain.validation.ConversionOutputValidators;
import com.myhomelibcorp.organizer.apply.OrganizerOutputValidationPort;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/** Shares the production conversion validators with Library Organizer instead of duplicating rules. */
@Component
public final class ToolchainOrganizerOutputValidationAdapter implements OrganizerOutputValidationPort {
    private final ConversionOutputValidators validators;

    public ToolchainOrganizerOutputValidationAdapter(ConversionOutputValidators validators) {
        this.validators = Objects.requireNonNull(validators, "validators");
    }

    @Override
    public void validate(Path file, String targetFormat, long maxOutputBytes) throws Exception {
        validators.forFormat(targetFormat).validate(file, maxOutputBytes);
    }
}
