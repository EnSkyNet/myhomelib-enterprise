package com.myhomelibcorp.converter.toolchain.validation;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

@Component
public class PdfOutputValidator implements ConversionOutputValidator {
    @Override public boolean supports(String format) { return "pdf".equalsIgnoreCase(format); }

    @Override public void validate(Path file, long maxOutputBytes) throws ConversionToolchainException {
        long size = ValidationSupport.validateRegularFile(file, maxOutputBytes);
        try {
            byte[] head = new byte[(int)Math.min(8, size)];
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { in.readNBytes(head, 0, head.length); }
            if (!new String(head, StandardCharsets.US_ASCII).startsWith("%PDF-")) invalid("PDF signature is missing");
            int tailSize = (int)Math.min(2048, size);
            byte[] tail = new byte[tailSize];
            try (var channel = Files.newByteChannel(file, LinkOption.NOFOLLOW_LINKS)) {
                channel.position(size - tailSize);
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(tail);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            }
            if (!new String(tail, StandardCharsets.ISO_8859_1).contains("%%EOF")) invalid("PDF EOF marker is missing");
        } catch (ConversionToolchainException e) { throw e; }
        catch (Exception e) { throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID, "PDF output cannot be inspected", e); }
    }

    private static void invalid(String message) throws ConversionToolchainException {
        throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID, message);
    }
}
