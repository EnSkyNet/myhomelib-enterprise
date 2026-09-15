package com.myhomelibcorp.shared.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reports the actual JVM/native charset before application services are started.
 *
 * <p>Changing {@code file.encoding} at runtime does not reliably change the JVM default charset.
 * Launchers therefore request UTF-8 and this guard makes a misconfigured environment explicit
 * instead of silently corrupting non-ASCII paths or text.</p>
 */
public final class RuntimeEncodingGuard {
    public static final String WARNING_PROPERTY = "myhomelib.encoding.warning";

    private RuntimeEncodingGuard() { }

    public static Status inspect() {
        Charset defaultCharset = Charset.defaultCharset();
        String nativeEncoding = System.getProperty("native.encoding", "").trim();
        boolean utf8Default = StandardCharsets.UTF_8.equals(defaultCharset);
        boolean utf8Native = nativeEncoding.isEmpty() || isUtf8Name(nativeEncoding);
        return new Status(defaultCharset.name(), nativeEncoding, utf8Default, utf8Native);
    }

    public static Status warnIfNeeded() {
        Status status = inspect();
        if (status.safeForUnicode()) {
            System.clearProperty(WARNING_PROPERTY);
            return status;
        }
        String message = "MYHOMELIB_ENCODING_WARNING: defaultCharset=" + status.defaultCharset()
                + ", native.encoding=" + (status.nativeEncoding().isBlank() ? "<unset>" : status.nativeEncoding())
                + ", os.name=" + System.getProperty("os.name", "")
                + ". UTF-8 is required for JVM text I/O and, on non-Windows hosts, for native filesystem path encoding; "
                + "non-ASCII paths/text may be unsafe in this runtime.";
        System.setProperty(WARNING_PROPERTY, message);
        System.err.println(message);
        return status;
    }

    private static boolean isUtf8Name(String value) {
        String normalized = value.toUpperCase(Locale.ROOT).replace("_", "-");
        return normalized.equals("UTF-8") || normalized.equals("UTF8");
    }

    public record Status(
            String defaultCharset,
            String nativeEncoding,
            boolean utf8Default,
            boolean utf8Native
    ) {
        /**
         * Java 18+ normally uses UTF-8 as the default charset (JEP 400). Windows NIO paths use
         * the Unicode Win32 API, so a legacy {@code native.encoding} code page is diagnostic
         * only there. On Unix-like hosts the native/JNU charset is also used to encode path
         * bytes; a POSIX/US-ASCII locale therefore cannot safely represent Cyrillic filenames
         * even when {@code file.encoding=UTF-8}.
         */
        public boolean safeForUnicode() {
            return safeForUnicode(System.getProperty("os.name", ""));
        }

        boolean safeForUnicode(String osName) {
            boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
            return utf8Default && (windows || utf8Native);
        }
    }
}
