package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraph;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraphAnalyzer;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the declared OPF package version without mutating the publication. */
@Component
public final class EpubPackageVersionInspector {
    private final EpubResourceGraphAnalyzer graphAnalyzer;

    public EpubPackageVersionInspector(EpubResourceGraphAnalyzer graphAnalyzer) {
        this.graphAnalyzer = graphAnalyzer;
    }

    public String packageVersion(Path epub) throws Exception {
        EpubResourceGraph graph = graphAnalyzer.analyze(epub);
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry packageEntry = zip.getEntry(graph.packagePath());
            if (packageEntry == null) throw new IllegalArgumentException("EPUB package document is missing");
            try (InputStream input = zip.getInputStream(packageEntry)) {
                XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(input);
                try {
                    while (reader.hasNext()) {
                        int event = reader.next();
                        if (event != XMLStreamConstants.START_ELEMENT) continue;
                        if (!"package".equals(reader.getLocalName())) {
                            throw new IllegalArgumentException("EPUB OPF root is not package");
                        }
                        String version = attribute(reader, "version");
                        if (version == null || version.isBlank()) {
                            throw new IllegalArgumentException("EPUB package version is missing");
                        }
                        return version.strip();
                    }
                } finally {
                    reader.close();
                }
            }
        }
        throw new IllegalArgumentException("EPUB package document is empty");
    }

    public boolean isEpub34(String version) {
        if (version == null) return false;
        String normalized = version.strip();
        return normalized.equals("3.4") || normalized.startsWith("3.4.");
    }

    private String attribute(XMLStreamReader reader, String name) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (name.equals(reader.getAttributeLocalName(index))) return reader.getAttributeValue(index);
        }
        return null;
    }
}
