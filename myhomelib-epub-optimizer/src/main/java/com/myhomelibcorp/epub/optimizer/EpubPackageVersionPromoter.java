package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.fs.EpubPathSafety;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceReplacementWriter;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Namespace;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Promotes an already-EPUB-3 publication to the experimental 3.4 package declaration without
 * changing its manifest, spine, navigation, metadata or resource paths. EPUB 2 is intentionally
 * rejected: changing only its OPF version is not a safe format upgrade.
 */
@Component
public final class EpubPackageVersionPromoter {
    private static final String TARGET_VERSION = "3.4";

    private final EpubResourceReplacementWriter replacementWriter;

    public EpubPackageVersionPromoter(EpubResourceReplacementWriter replacementWriter) {
        this.replacementWriter = Objects.requireNonNull(replacementWriter, "replacementWriter");
    }

    public void promoteTo34(Path source, Path target, String packagePath, Path workspace) throws Exception {
        Path safeSource = EpubPathSafety.requireSafeRegularFile(source, "EPUB 3.4 promotion source");
        Path safeTarget = EpubPathSafety.prepareTarget(target, "EPUB 3.4 promotion target");
        if (packagePath == null || packagePath.isBlank()) {
            throw new IllegalArgumentException("EPUB package path is required for 3.4 promotion");
        }
        Path safeWorkspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize();
        Path replacement = EpubPathSafety.prepareTarget(
                safeWorkspace.resolve("package-3.4.opf"), "EPUB 3.4 package replacement");

        writePromotedPackage(safeSource, packagePath, replacement);
        replacementWriter.replace(safeSource, safeTarget, Map.of(packagePath, replacement));
    }

    private void writePromotedPackage(Path source, String packagePath, Path replacement) throws Exception {
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(packagePath);
            if (entry == null || entry.isDirectory()) {
                throw new IllegalArgumentException("EPUB package document is missing: " + packagePath);
            }
            if (ArchiveSafetyLimits.declaredEntryTooLarge(entry.getSize())) {
                throw new IllegalArgumentException("EPUB package document exceeds safe size limit");
            }
            try (InputStream input = zip.getInputStream(entry);
                 OutputStream output = EpubPathSafety.openFreshOutputStream(replacement, "EPUB 3.4 package replacement")) {
                rewritePackageVersion(input, output);
            }
        }
    }

    private void rewritePackageVersion(InputStream input, OutputStream output) throws Exception {
        XMLEventReader reader = SecureXmlInputFactory.create(false, false).createXMLEventReader(input);
        XMLEventWriter writer = XMLOutputFactory.newFactory().createXMLEventWriter(output, StandardCharsets.UTF_8.name());
        XMLEventFactory events = XMLEventFactory.newFactory();
        boolean rootSeen = false;
        boolean versionChanged = false;
        try {
            while (reader.hasNext()) {
                checkCancelled();
                XMLEvent event = reader.nextEvent();
                if (event.isStartDocument()) {
                    writer.add(events.createStartDocument(StandardCharsets.UTF_8.name(), "1.0"));
                    continue;
                }
                if (event.isStartElement() && !rootSeen) {
                    rootSeen = true;
                    StartElement root = event.asStartElement();
                    if (!"package".equals(root.getName().getLocalPart())) {
                        throw new IllegalArgumentException("EPUB OPF root is not package");
                    }
                    List<Attribute> attributes = new ArrayList<>();
                    Iterator<?> rawAttributes = root.getAttributes();
                    while (rawAttributes.hasNext()) {
                        Attribute attribute = (Attribute) rawAttributes.next();
                        if ("version".equals(attribute.getName().getLocalPart())) {
                            QName name = attribute.getName();
                            attributes.add(events.createAttribute(name, TARGET_VERSION));
                            versionChanged = true;
                        } else {
                            attributes.add(attribute);
                        }
                    }
                    if (!versionChanged) {
                        attributes.add(events.createAttribute("version", TARGET_VERSION));
                        versionChanged = true;
                    }
                    List<Namespace> namespaces = new ArrayList<>();
                    Iterator<?> rawNamespaces = root.getNamespaces();
                    while (rawNamespaces.hasNext()) namespaces.add((Namespace) rawNamespaces.next());
                    writer.add(events.createStartElement(root.getName(), attributes.iterator(), namespaces.iterator()));
                    continue;
                }
                writer.add(event);
            }
            writer.flush();
        } finally {
            try { reader.close(); } finally { writer.close(); }
        }
        if (!rootSeen || !versionChanged) {
            throw new IllegalArgumentException("EPUB package version could not be promoted to 3.4");
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("EPUB 3.4 package promotion cancelled");
        }
    }
}
