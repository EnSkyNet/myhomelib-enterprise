package com.myhomelibcorp.reader.format.txt;

import com.myhomelibcorp.shared.text.TextStreamDecoder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;

/** Reader compatibility facade over the shared streaming text decoder. */
public final class TextEncodingDetector {
    private TextEncodingDetector() { }

    public static BufferedReader open(InputStream input, String preferredEncoding) throws IOException {
        return TextStreamDecoder.open(input, preferredEncoding);
    }
}
