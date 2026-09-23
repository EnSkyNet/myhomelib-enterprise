package com.myhomelibcorp.converter.toolchain.validation;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import org.springframework.stereotype.Component;

import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

@Component
public class TextOutputValidator implements ConversionOutputValidator {
    @Override public boolean supports(String format) { return "txt".equalsIgnoreCase(format); }

    @Override public void validate(Path file, long maxOutputBytes) throws ConversionToolchainException {
        ValidationSupport.validateRegularFile(file, maxOutputBytes);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), decoder)) {
                char[] buffer = new char[32 * 1024];
                while (reader.read(buffer) >= 0) {
                    // Strict decoder validates incrementally without loading the whole output into memory.
                }
            }
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID, "TXT output is not valid UTF-8", e);
        } catch (Exception e) {
            if (e instanceof ConversionToolchainException c) throw c;
            throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "Cannot validate TXT output", e);
        }
    }
}
