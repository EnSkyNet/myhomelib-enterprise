package com.myhomelibcorp.converter.toolchain.validation;

import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class BasicOutputValidator implements ConversionOutputValidator {
    @Override public boolean supports(String format) { return true; }
    @Override public void validate(Path file, long maxOutputBytes) throws ConversionToolchainException {
        ValidationSupport.validateRegularFile(file, maxOutputBytes);
    }
}
