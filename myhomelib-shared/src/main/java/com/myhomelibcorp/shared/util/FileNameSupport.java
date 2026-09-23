package com.myhomelibcorp.shared.util;

import java.util.Locale;

/** Path-string helpers that work for both '/' and '\\' separators. */
public final class FileNameSupport {
    private FileNameSupport() { }

    public static String extension(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Filesystem-independent basename for logical archive/resource names. */
    public static String baseName(String name) {
        if (name == null || name.isBlank()) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
    /**
     * Decodes the literal {@code #Uhhhh} convention used by some legacy FB2/ZIP producers for
     * non-ASCII UTF-16 code units. ASCII escapes are intentionally left untouched so a literal
     * filename such as {@code #U0041.txt} is not silently renamed and encoded separators cannot
     * change path structure. Malformed and unpaired-surrogate sequences are preserved verbatim.
     */
    public static String decodeHashUnicodeEscapes(String value) {
        if (value == null || value.indexOf("#U") < 0) return value;

        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            int first = escapedCodeUnitAt(value, i);
            if (first < 0x80) {
                decoded.append(value.charAt(i++));
                continue;
            }
            char firstChar = (char) first;
            if (Character.isHighSurrogate(firstChar)) {
                int second = escapedCodeUnitAt(value, i + 6);
                if (second >= 0 && Character.isLowSurrogate((char) second)) {
                    decoded.append(firstChar).append((char) second);
                    i += 12;
                    continue;
                }
                decoded.append(value.charAt(i++));
                continue;
            }
            if (Character.isLowSurrogate(firstChar) || Character.isISOControl(firstChar)) {
                decoded.append(value.charAt(i++));
                continue;
            }
            decoded.append(firstChar);
            i += 6;
        }
        return decoded.toString();
    }

    private static int escapedCodeUnitAt(String value, int index) {
        if (index < 0 || index + 6 > value.length()
                || value.charAt(index) != '#' || value.charAt(index + 1) != 'U') return -1;
        int result = 0;
        for (int i = index + 2; i < index + 6; i++) {
            int digit = Character.digit(value.charAt(i), 16);
            if (digit < 0) return -1;
            result = (result << 4) | digit;
        }
        return result;
    }

}
