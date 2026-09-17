package com.myhomelibcorp.reader.render.pdf;

import java.io.IOException;

/** Signals that the PDF is encrypted and requires a valid password before it can be opened. */
public final class PdfPasswordRequiredException extends IOException {
    public PdfPasswordRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
