package com.myhomelibcorp.converter.toolchain.validation;

import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;

import java.nio.file.Path;

public interface ConversionOutputValidator {
    boolean supports(String format);
    void validate(Path file, long maxOutputBytes) throws ConversionToolchainException;
}
