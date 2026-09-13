package com.myhomelibcorp.plugin.api;

import java.io.IOException;
import java.nio.file.Path;

/** Provider-neutral export extension point. */
public interface ExportProvider {
    String id();
    boolean supports(String targetFormat);
    Path export(Path source, Path destination, String targetFormat, PluginOperationContext context) throws IOException;
}
