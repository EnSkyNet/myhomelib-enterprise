package com.myhomelibcorp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Headless release-package smoke used by CI after jpackage. It deliberately
 * avoids Spring/JavaFX startup so it can run on clean headless workers while
 * still proving that the packaged launcher can execute the application jar and
 * resolve resources from dependent modules.
 */
final class ReleaseSmokeCheck {
    static final String SUCCESS_MARKER = "MYHOMELIB_RELEASE_SMOKE_OK";

    private ReleaseSmokeCheck() {
    }

    static void run() throws Exception {
        List<String> requiredResources = List.of(
                "view/MainView.fxml",
                "db/migration/V1__init.sql",
                "help/backup.md",
                "lang/default/uk.json"
        );
        ClassLoader loader = ReleaseSmokeCheck.class.getClassLoader();
        for (String resource : requiredResources) {
            try (InputStream stream = loader.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing packaged resource: " + resource);
                }
                byte[] prefix = stream.readNBytes(64);
                if (prefix.length == 0 || new String(prefix, StandardCharsets.UTF_8).isBlank()) {
                    throw new IllegalStateException("Empty packaged resource: " + resource);
                }
            }
        }
        System.out.println(SUCCESS_MARKER);
    }
}
