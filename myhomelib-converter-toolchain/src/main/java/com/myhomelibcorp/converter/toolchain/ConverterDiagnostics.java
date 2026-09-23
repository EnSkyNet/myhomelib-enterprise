package com.myhomelibcorp.converter.toolchain;

import com.myhomelibcorp.application.conversion.diagnostics.ConverterDiagnosticsPort;
import com.myhomelibcorp.application.conversion.diagnostics.ConverterDiagnosticsRequest;
import com.myhomelibcorp.application.conversion.diagnostics.ConverterToolStatus;
import com.myhomelibcorp.converter.toolchain.discovery.ConverterToolRegistry;
import com.myhomelibcorp.converter.toolchain.discovery.ToolInstallation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class ConverterDiagnostics implements ConverterDiagnosticsPort {
    private final ConverterToolRegistry tools;

    public ConverterDiagnostics(ConverterToolRegistry tools) { this.tools = tools; }

    @Override
    public List<ConverterToolStatus> snapshot() {
        Instant checkedAt = Instant.now();
        return List.of(entry(tools.fbc(), checkedAt), entry(tools.pandoc(), checkedAt), entry(tools.libmobi(), checkedAt));
    }

    @Override public List<ConverterToolStatus> refresh() { tools.refresh(); return snapshot(); }

    @Override
    public List<ConverterToolStatus> probe(ConverterDiagnosticsRequest request) {
        ConverterDiagnosticsRequest effective = request == null ? new ConverterDiagnosticsRequest("", "", "") : request;
        Instant checkedAt = Instant.now();
        return List.of(entry(tools.probeFbc(effective.fbcExecutable()), checkedAt),
                entry(tools.probePandoc(effective.pandocExecutable()), checkedAt),
                entry(tools.probeLibmobi(effective.libmobiExecutable()), checkedAt));
    }

    private ConverterToolStatus entry(ToolInstallation tool, Instant checkedAt) {
        return new ConverterToolStatus(tool.id(), tool.available(), tool.executable() == null ? "" : tool.executable().toString(),
                tool.version().raw(), tool.diagnostic(), checkedAt);
    }
}
