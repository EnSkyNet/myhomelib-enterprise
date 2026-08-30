package com.myhomelibcorp.shared.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Shared strict UTF-8 validation for format/encoding detection. */
public final class Utf8Validator {
    private Utf8Validator() { }

    public static boolean isValid(byte[] sample) {
        if (sample == null) return false;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sample));
            return true;
        } catch (CharacterCodingException ignored) {
            return false;
        }
    }
}
