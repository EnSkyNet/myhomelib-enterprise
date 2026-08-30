package com.myhomelibcorp.reader.format.txt;

import com.myhomelibcorp.shared.util.Utf8Validator;
import java.io.*;
import java.nio.charset.*;
import java.util.List;

/** Streaming text decoder for legacy MyHomeLib text collections. */
public final class TextEncodingDetector {
    private static final int SAMPLE_LIMIT = 16 * 1024;

    private TextEncodingDetector() { }

    public static BufferedReader open(InputStream input, String preferredEncoding) throws IOException {
        if (input == null) throw new IOException("Text input is null");
        PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(input, 64 * 1024), SAMPLE_LIMIT);
        byte[] sample = in.readNBytes(SAMPLE_LIMIT);
        Detected detected = preferred(preferredEncoding, sample);
        int skip = Math.min(detected.bomBytes(), sample.length);
        if (sample.length > skip) in.unread(sample, skip, sample.length - skip);
        return new BufferedReader(new InputStreamReader(in, detected.charset()), 64 * 1024);
    }

    private static Detected preferred(String preferredEncoding, byte[] sample) {
        Detected bom = detectBom(sample);
        if (bom != null) return bom;
        if (preferredEncoding != null && !preferredEncoding.isBlank()) {
            try { return new Detected(Charset.forName(preferredEncoding.trim()), 0); }
            catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) { }
        }
        if (Utf8Validator.isValid(sample)) return new Detected(StandardCharsets.UTF_8, 0);

        List<Charset> legacy = List.of(Charset.forName("windows-1251"), Charset.forName("CP866"));
        Charset best = legacy.getFirst();
        int bestScore = Integer.MIN_VALUE;
        for (Charset charset : legacy) {
            int score = textScore(new String(sample, charset));
            if (score > bestScore) {
                bestScore = score;
                best = charset;
            }
        }
        return new Detected(best, 0);
    }

    private static Detected detectBom(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf)
            return new Detected(StandardCharsets.UTF_8, 3);
        if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe)
            return new Detected(StandardCharsets.UTF_16LE, 2);
        if (b.length >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff)
            return new Detected(StandardCharsets.UTF_16BE, 2);
        return null;
    }


    private static int textScore(String text) {
        int score = 0;
        String common = "оеаинтсрвлкмдпуяыьгзбчйхжшюцщэфъёіїєґ";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char lower = Character.toLowerCase(c);
            if (Character.isLetterOrDigit(c)) score += 1;
            if ((c >= 'А' && c <= 'я') || "ІіЇїЄєҐґЁё".indexOf(c) >= 0) score += 2;
            if (common.indexOf(lower) >= 0) score += 2;
            if (Character.isWhitespace(c) || ",.;:!?-'\"/()[]".indexOf(c) >= 0) score += 1;
            if (Character.isISOControl(c) && !Character.isWhitespace(c)) score -= 6;
            if (c >= 0x2500 && c <= 0x259f) score -= 4;
            if (c == '\ufffd') score -= 20;
        }
        return score;
    }

    private record Detected(Charset charset, int bomBytes) { }
}
