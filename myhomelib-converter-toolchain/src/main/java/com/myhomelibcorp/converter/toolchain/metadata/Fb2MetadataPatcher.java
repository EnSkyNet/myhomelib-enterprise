package com.myhomelibcorp.converter.toolchain.metadata;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.converter.toolchain.fs.ConversionPathSafety;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

/**
 * Conservative, streaming FB2 metadata merge used after Pandoc writes FB2.
 *
 * <p>The file is scanned once to discover which metadata is already present and rewritten in a
 * second bounded-memory pass. Existing metadata always wins; MyHomeLib only inserts fields that
 * disappeared during conversion. The streaming implementation deliberately avoids building a DOM
 * because FB2 files can contain very large base64 binaries.</p>
 */
@Component
public final class Fb2MetadataPatcher {
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final Map<String, Integer> TITLE_INFO_ORDER = titleInfoOrder();
    private static final Map<String, Integer> PUBLISH_INFO_ORDER = publishInfoOrder();

    public void patchMissing(Path fb2, CanonicalBookMetadata metadata) throws ConversionToolchainException {
        patchMissing(fb2, metadata, () -> false);
    }

    public void patchMissing(Path fb2, CanonicalBookMetadata metadata, BooleanSupplier cancelled)
            throws ConversionToolchainException {
        Objects.requireNonNull(fb2, "fb2");
        Objects.requireNonNull(metadata, "metadata");
        BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
        Path source = fb2.toAbsolutePath().normalize();

        try {
            source = ConversionPathSafety.requireSafeRegularFile(source, "FB2 metadata merge source");
            checkCancelled(cancellation);
            rejectDoctype(source, cancellation);
            source = ConversionPathSafety.requireSafeRegularFile(source, "FB2 metadata merge source");
            Scan scan = scan(source, cancellation);
            CoverPlan cover = coverPlan(metadata.cover(), scan.binaryIds(), scan.titleFieldsWithValue().contains("coverpage"));
            if (!needsChanges(scan, metadata, cover)) return;

            Path parent = source.getParent();
            if (parent == null) {
                throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                        "Cannot merge metadata: FB2 output has no parent directory");
            }
            ConversionPathSafety.requireSafeDirectoryPath(parent, "FB2 metadata merge parent");
            String originalHash = sha256(source, cancellation);
            Path tmp = parent.resolve("." + source.getFileName() + ".metadata-" + UUID.randomUUID() + ".tmp");
            Path previous = parent.resolve("." + source.getFileName() + ".metadata-original-" + UUID.randomUUID() + ".tmp");
            boolean published = false;
            boolean detached = false;
            try {
                rewrite(source, tmp, scan, metadata, cover, cancellation);
                checkCancelled(cancellation);
                ConversionPathSafety.requireSafeRegularFile(source, "FB2 metadata merge source");
                ConversionPathSafety.requireSafeDirectoryPath(parent, "FB2 metadata merge parent");
                ConversionPathSafety.requireSafeRegularFile(tmp, "FB2 metadata merge candidate");

                ConversionPathSafety.moveNoReplace(source, previous, "FB2 metadata original detach");
                detached = true;
                if (!originalHash.equalsIgnoreCase(sha256(previous, cancellation))) {
                    restoreDetached(previous, source, "FB2 metadata original changed while detaching");
                    detached = false;
                    throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                            "Cannot merge metadata: FB2 output changed during publication");
                }

                ConversionPathSafety.moveNoReplace(tmp, source, "FB2 metadata patched publication");
                published = true;
                ConversionPathSafety.requireSafeRegularFile(source, "FB2 metadata merge result");
                checkCancelled(cancellation);
                if (!originalHash.equalsIgnoreCase(sha256(previous, cancellation))) {
                    throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE,
                            "Cannot merge metadata: detached original changed during publication");
                }
                Files.delete(previous);
                detached = false;
            } catch (Exception failure) {
                if (published) {
                    // The newly published file is private workspace output. Detach it instead of replacing
                    // anything at the source path, then restore the verified original only if the path is free.
                    Path evidence = parent.resolve("." + source.getFileName() + ".metadata-failed-" + UUID.randomUUID() + ".tmp");
                    try {
                        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS))
                            ConversionPathSafety.moveNoReplace(source, evidence, "FB2 metadata failed publication evidence");
                    } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                }
                if (detached && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS))
                            restoreDetached(previous, source, "FB2 metadata rollback");
                        else failure.addSuppressed(new IllegalStateException(
                                "FB2 metadata rollback source path is occupied; original retained at " + previous));
                    } catch (Exception restore) { failure.addSuppressed(restore); }
                }
                throw failure;
            } finally {
                try { if (Files.exists(tmp, LinkOption.NOFOLLOW_LINKS)) Files.delete(tmp); } catch (Exception ignored) { }
            }
        } catch (java.util.concurrent.CancellationException cancelledFailure) {
            throw new ConversionToolchainException(ConversionFailureKind.CANCELLED,
                    "FB2 metadata merge cancelled", cancelledFailure);
        } catch (ConversionToolchainException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                    "Cannot merge MyHomeLib metadata into Pandoc FB2 output", failure);
        }
    }


    /** Rejects DTD syntax before StAX so parser-specific SUPPORT_DTD behavior cannot downgrade it to a generic parse error. */
    private void rejectDoctype(Path source, BooleanSupplier cancelled) throws Exception {
        byte[][] patterns = {
                "<!DOCTYPE".getBytes(StandardCharsets.US_ASCII),
                new byte[]{'<',0,'!',0,'D',0,'O',0,'C',0,'T',0,'Y',0,'P',0,'E',0},
                new byte[]{0,'<',0,'!',0,'D',0,'O',0,'C',0,'T',0,'Y',0,'P',0,'E'}
        };
        int[] matched = new int[patterns.length];
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read <= 0) continue;
                total += read;
                if ((total & 0xfffffL) < read) checkCancelled(cancelled);
                for (int i = 0; i < read; i++) {
                    byte value = buffer[i];
                    for (int p = 0; p < patterns.length; p++) {
                        byte[] pattern = patterns[p];
                        int index = matched[p];
                        if (value == pattern[index]) {
                            index++;
                            if (index == pattern.length) {
                                throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED,
                                        "FB2 metadata merge rejects DTD declarations");
                            }
                            matched[p] = index;
                        } else {
                            matched[p] = value == pattern[0] ? 1 : 0;
                        }
                    }
                }
            }
        }
    }

    private Scan scan(Path source, BooleanSupplier cancelled) throws Exception {
        XMLInputFactory factory = secureInputFactory();
        boolean description = false;
        boolean titleInfo = false;
        boolean publishInfo = false;
        Set<String> titleFields = new HashSet<>();
        Set<String> titleFieldsWithValue = new HashSet<>();
        Set<String> publishFields = new HashSet<>();
        Set<String> publishFieldsWithValue = new HashSet<>();
        Set<String> binaryIds = new HashSet<>();
        Set<String> existingGenres = new HashSet<>();
        Set<String> existingAuthors = new HashSet<>();
        StringBuilder identityText = new StringBuilder();
        int emptyGenreCount = 0;
        int emptyAuthorCount = 0;
        boolean currentAuthorHasChild = false;
        int sequenceCount = 0;
        boolean sequenceNameWithValue = false;
        boolean sequenceNumberWithValue = false;
        String rootNamespace = "";
        String rootPrefix = "";
        int depth = 0;
        int descriptionDepth = -1;
        int titleDepth = -1;
        int publishDepth = -1;
        int titleFieldDepth = -1;
        int publishFieldDepth = -1;
        String titleField = "";
        String publishField = "";
        long events = 0L;

        try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if ((++events & 0x0fffL) == 0L) checkCancelled(cancelled);
                    if (event == XMLStreamConstants.DTD) {
                        throw new ConversionToolchainException(ConversionFailureKind.SECURITY_REJECTED,
                                "FB2 metadata merge rejects DTD declarations");
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        String local = reader.getLocalName();
                        if (depth == 1) {
                            if (!"FictionBook".equals(local)) {
                                throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                                        "Cannot merge metadata: FB2 root element is not FictionBook");
                            }
                            rootNamespace = text(reader.getNamespaceURI());
                            rootPrefix = text(reader.getPrefix());
                        }
                        if (depth == 2 && "description".equals(local)) {
                            description = true;
                            descriptionDepth = depth;
                        } else if (descriptionDepth > 0 && depth == descriptionDepth + 1 && "title-info".equals(local)) {
                            titleInfo = true;
                            titleDepth = depth;
                        } else if (descriptionDepth > 0 && depth == descriptionDepth + 1 && "publish-info".equals(local)) {
                            publishInfo = true;
                            publishDepth = depth;
                        } else if (titleDepth > 0 && depth == titleDepth + 1) {
                            titleFields.add(local);
                            titleField = local;
                            titleFieldDepth = depth;
                            if ("genre".equals(local) || "author".equals(local)) identityText.setLength(0);
                            if ("author".equals(local)) currentAuthorHasChild = false;
                            if (elementHasValue(reader)) titleFieldsWithValue.add(local);
                            if ("sequence".equals(local)) {
                                sequenceCount++;
                                sequenceNameWithValue |= !blank(reader.getAttributeValue(null, "name"));
                                sequenceNumberWithValue |= !blank(reader.getAttributeValue(null, "number"));
                            }
                        } else if (publishDepth > 0 && depth == publishDepth + 1) {
                            publishFields.add(local);
                            publishField = local;
                            publishFieldDepth = depth;
                            if (elementHasValue(reader)) publishFieldsWithValue.add(local);
                        } else {
                            if (titleFieldDepth > 0 && depth > titleFieldDepth && "author".equals(titleField)) {
                                currentAuthorHasChild = true;
                                appendIdentitySeparator(identityText);
                            }
                            if (titleFieldDepth > 0 && depth > titleFieldDepth && elementHasValue(reader))
                                titleFieldsWithValue.add(titleField);
                            if (publishFieldDepth > 0 && depth > publishFieldDepth && elementHasValue(reader))
                                publishFieldsWithValue.add(publishField);
                        }
                        if ("binary".equals(local)) {
                            String id = reader.getAttributeValue(null, "id");
                            if (id != null && !id.isBlank()) binaryIds.add(id);
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                        if (!reader.isWhiteSpace() && !reader.getText().isBlank()) {
                            if (titleFieldDepth > 0) {
                                titleFieldsWithValue.add(titleField);
                                if ("genre".equals(titleField) || "author".equals(titleField)) {
                                    appendIdentityText(identityText, reader.getText());
                                }
                            }
                            if (publishFieldDepth > 0) publishFieldsWithValue.add(publishField);
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (depth == titleFieldDepth) {
                            String key = identityKey(identityText.toString());
                            if ("genre".equals(titleField)) {
                                if (!key.isBlank()) existingGenres.add(key); else emptyGenreCount++;
                            }
                            if ("author".equals(titleField)) {
                                if (!key.isBlank()) existingAuthors.add(key);
                                else if (!currentAuthorHasChild) emptyAuthorCount++;
                                currentAuthorHasChild = false;
                            }
                            titleFieldDepth = -1;
                            titleField = "";
                            identityText.setLength(0);
                        }
                        if (depth == publishFieldDepth) { publishFieldDepth = -1; publishField = ""; }
                        if (depth == titleDepth) titleDepth = -1;
                        if (depth == publishDepth) publishDepth = -1;
                        if (depth == descriptionDepth) descriptionDepth = -1;
                        depth--;
                    }
                }
            } finally {
                reader.close();
            }
        }
        if (depth != 0) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                    "Cannot merge metadata: malformed FB2 element nesting");
        }
        return new Scan(description, titleInfo, publishInfo, Set.copyOf(titleFields), Set.copyOf(titleFieldsWithValue),
                Set.copyOf(publishFields), Set.copyOf(publishFieldsWithValue), Set.copyOf(binaryIds),
                Set.copyOf(existingGenres), Set.copyOf(existingAuthors), emptyGenreCount, emptyAuthorCount, sequenceCount,
                sequenceNameWithValue, sequenceNumberWithValue, rootNamespace, rootPrefix);
    }

    private void restoreDetached(Path detached, Path target, String role) throws Exception {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        ConversionPathSafety.moveNoReplace(detached, target, role);
    }

    private String sha256(Path file, BooleanSupplier cancelled) throws Exception {
        Path safe = ConversionPathSafety.requireSafeRegularFile(file, "FB2 metadata hash source");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream in = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = in.read(buffer)) >= 0; ) {
                checkCancelled(cancelled);
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void rewrite(Path source, Path target, Scan scan, CanonicalBookMetadata metadata, CoverPlan cover,
                         BooleanSupplier cancelled) throws Exception {
        XMLInputFactory inputFactory = secureInputFactory();
        XMLOutputFactory outputFactory = XMLOutputFactory.newFactory();
        trySetOutputProperty(outputFactory, XMLOutputFactory.IS_REPAIRING_NAMESPACES, false);

        try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = ConversionPathSafety.openFreshOutputStream(target, "FB2 metadata merge candidate")) {
            XMLStreamReader reader = inputFactory.createXMLStreamReader(in);
            XMLStreamWriter writer = outputFactory.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
            Set<String> emittedTitle = new HashSet<>();
            Set<String> emittedPublish = new HashSet<>();
            int depth = 0;
            int descriptionDepth = -1;
            int titleDepth = -1;
            int publishDepth = -1;
            int titleFieldDepth = -1;
            int publishFieldDepth = -1;
            String titleField = "";
            String publishField = "";
            boolean generatedDescription = false;
            boolean generatedTitle = false;
            boolean generatedPublish = false;
            int emptyGenreFillIndex = 0;
            int emptyAuthorFillIndex = 0;
            boolean currentAuthorHasChild = false;
            boolean wroteDocument = false;
            long events = 0L;

            try {
                writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
                wroteDocument = true;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if ((++events & 0x0fffL) == 0L) checkCancelled(cancelled);
                    switch (event) {
                        case XMLStreamConstants.START_DOCUMENT, XMLStreamConstants.END_DOCUMENT -> { }
                        case XMLStreamConstants.DTD -> throw new ConversionToolchainException(
                                ConversionFailureKind.SECURITY_REJECTED, "FB2 metadata merge rejects DTD declarations");
                        case XMLStreamConstants.START_ELEMENT -> {
                            int newDepth = depth + 1;
                            String local = reader.getLocalName();

                            if (titleDepth > 0 && newDepth == titleDepth + 1) {
                                emitTitleBefore(writer, scan, metadata, cover, emittedTitle,
                                        TITLE_INFO_ORDER.getOrDefault(local, Integer.MAX_VALUE));
                            }
                            if (publishDepth > 0 && newDepth == publishDepth + 1) {
                                emitPublishBefore(writer, scan, metadata, emittedPublish,
                                        PUBLISH_INFO_ORDER.getOrDefault(local, Integer.MAX_VALUE));
                            }
                            if (descriptionDepth > 0 && newDepth == descriptionDepth + 1
                                    && !scan.publishInfo() && !generatedPublish && "custom-info".equals(local)
                                    && hasPublishMetadata(metadata)) {
                                writePublishInfo(writer, scan, metadata, emittedPublish);
                                generatedPublish = true;
                            }

                            boolean directTitleField = titleDepth > 0 && newDepth == titleDepth + 1;
                            Map<String, String> attributeOverrides = Map.of();
                            if (directTitleField && "sequence".equals(local) && scan.sequenceCount() == 1) {
                                Map<String, String> overrides = new HashMap<>();
                                if (!scan.sequenceNameWithValue() && !blank(metadata.series())
                                        && blank(reader.getAttributeValue(null, "name"))) {
                                    overrides.put("name", metadata.series());
                                }
                                if (!scan.sequenceNumberWithValue() && metadata.sequenceNumber() != null
                                        && metadata.sequenceNumber() > 0
                                        && blank(reader.getAttributeValue(null, "number"))) {
                                    overrides.put("number", Integer.toString(metadata.sequenceNumber()));
                                }
                                attributeOverrides = Map.copyOf(overrides);
                            }
                            copyStartElement(reader, writer, attributeOverrides);
                            depth = newDepth;
                            if (directTitleField) {
                                titleField = local;
                                titleFieldDepth = depth;
                                if ("author".equals(local)) currentAuthorHasChild = false;
                            } else {
                                if (titleFieldDepth > 0 && depth > titleFieldDepth && "author".equals(titleField))
                                    currentAuthorHasChild = true;
                                if (publishDepth > 0 && depth == publishDepth + 1) {
                                    publishField = local;
                                    publishFieldDepth = depth;
                                }
                            }

                            if (depth == 1 && !scan.description() && !generatedDescription) {
                                writeGeneratedDescription(writer, scan, metadata, cover, emittedTitle, emittedPublish);
                                generatedDescription = true;
                                generatedTitle = true;
                                generatedPublish = hasPublishMetadata(metadata);
                            } else if (depth == 2 && "description".equals(local)) {
                                descriptionDepth = depth;
                                if (!scan.titleInfo() && !generatedTitle) {
                                    writeTitleInfo(writer, scan, metadata, cover, emittedTitle);
                                    generatedTitle = true;
                                }
                            } else if (descriptionDepth > 0 && depth == descriptionDepth + 1 && "title-info".equals(local)) {
                                titleDepth = depth;
                            } else if (descriptionDepth > 0 && depth == descriptionDepth + 1 && "publish-info".equals(local)) {
                                publishDepth = depth;
                            }
                        }
                        case XMLStreamConstants.END_ELEMENT -> {
                            if (depth == titleDepth) {
                                emitTitleBefore(writer, scan, metadata, cover, emittedTitle, Integer.MAX_VALUE);
                            }
                            if (depth == publishDepth) {
                                emitPublishBefore(writer, scan, metadata, emittedPublish, Integer.MAX_VALUE);
                            }
                            if (depth == descriptionDepth && !scan.publishInfo() && !generatedPublish
                                    && hasPublishMetadata(metadata)) {
                                writePublishInfo(writer, scan, metadata, emittedPublish);
                                generatedPublish = true;
                            }
                            if (depth == titleFieldDepth && !scan.titleFieldsWithValue().contains(titleField)) {
                                if ("genre".equals(titleField)) {
                                    fillEmptyGenre(writer, scan, metadata, emptyGenreFillIndex++);
                                } else if ("author".equals(titleField)) {
                                    if (!currentAuthorHasChild) fillEmptyAuthor(writer, scan, metadata, emptyAuthorFillIndex++);
                                } else {
                                    fillEmptyTitleField(writer, scan, metadata, cover, titleField);
                                }
                            }
                            if (depth == publishFieldDepth && !scan.publishFieldsWithValue().contains(publishField)) {
                                fillEmptyPublishField(writer, scan, metadata, publishField);
                            }
                            if (depth == 1 && cover.required()) {
                                writeCoverBinary(writer, scan, cover, cancelled);
                            }
                            writer.writeEndElement();
                            if (depth == titleFieldDepth) {
                                if ("author".equals(titleField)) currentAuthorHasChild = false;
                                titleFieldDepth = -1;
                                titleField = "";
                            }
                            if (depth == publishFieldDepth) { publishFieldDepth = -1; publishField = ""; }
                            if (depth == titleDepth) titleDepth = -1;
                            if (depth == publishDepth) publishDepth = -1;
                            if (depth == descriptionDepth) descriptionDepth = -1;
                            depth--;
                        }
                        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> writer.writeCharacters(reader.getText());
                        case XMLStreamConstants.CDATA -> writer.writeCData(reader.getText());
                        case XMLStreamConstants.COMMENT -> writer.writeComment(reader.getText());
                        case XMLStreamConstants.PROCESSING_INSTRUCTION -> writer.writeProcessingInstruction(
                                reader.getPITarget(), reader.getPIData() == null ? "" : reader.getPIData());
                        case XMLStreamConstants.ENTITY_REFERENCE -> writer.writeEntityRef(reader.getLocalName());
                        default -> { }
                    }
                }
                if (wroteDocument) writer.writeEndDocument();
                writer.flush();
            } finally {
                try { writer.close(); } finally { reader.close(); }
            }
        }
    }

    private void emitTitleBefore(XMLStreamWriter writer, Scan scan, CanonicalBookMetadata metadata,
                                 CoverPlan cover, Set<String> emitted, int beforeOrder) throws XMLStreamException {
        for (String field : List.of("genre", "author", "book-title", "annotation", "coverpage", "lang", "translator", "sequence")) {
            if (emitted.contains(field)) continue;
            if (!"genre".equals(field) && !"author".equals(field) && scan.titleFields().contains(field)) continue;
            int order = TITLE_INFO_ORDER.getOrDefault(field, Integer.MAX_VALUE);
            if (order >= beforeOrder) continue;
            switch (field) {
                case "genre" -> {
                    List<String> missing = missingGenres(scan, metadata);
                    for (int i = Math.min(scan.emptyGenreCount(), missing.size()); i < missing.size(); i++) {
                        simpleElement(writer, scan, "genre", missing.get(i));
                    }
                    if (!metadata.genres().isEmpty()) emitted.add(field);
                }
                case "author" -> {
                    List<CanonicalBookMetadata.CanonicalAuthor> missing = missingAuthors(scan, metadata);
                    for (int i = Math.min(scan.emptyAuthorCount(), missing.size()); i < missing.size(); i++) {
                        writePerson(writer, scan, "author", missing.get(i));
                    }
                    if (!metadata.authors().isEmpty()) emitted.add(field);
                }
                case "book-title" -> {
                    if (!blank(metadata.title())) { simpleElement(writer, scan, field, metadata.title()); emitted.add(field); }
                }
                case "annotation" -> {
                    if (!blank(metadata.annotation())) { writeAnnotation(writer, scan, metadata.annotation()); emitted.add(field); }
                }
                case "coverpage" -> {
                    if (cover.required()) { writeCoverPage(writer, scan, cover); emitted.add(field); }
                }
                case "lang" -> {
                    if (!blank(metadata.language())) { simpleElement(writer, scan, field, metadata.language()); emitted.add(field); }
                }
                case "translator" -> {
                    if (!blank(metadata.translators())) { writeTranslator(writer, scan, metadata.translators()); emitted.add(field); }
                }
                case "sequence" -> {
                    if (!blank(metadata.series())) { writeSequence(writer, scan, metadata.series(), metadata.sequenceNumber()); emitted.add(field); }
                }
                default -> { }
            }
        }
    }

    private void emitPublishBefore(XMLStreamWriter writer, Scan scan, CanonicalBookMetadata metadata,
                                   Set<String> emitted, int beforeOrder) throws XMLStreamException {
        for (String field : List.of("publisher", "year", "isbn")) {
            if (scan.publishFields().contains(field) || emitted.contains(field)) continue;
            int order = PUBLISH_INFO_ORDER.getOrDefault(field, Integer.MAX_VALUE);
            if (order >= beforeOrder) continue;
            String value = switch (field) {
                case "publisher" -> metadata.publisher();
                case "year" -> metadata.year() != null && metadata.year() > 0 ? Integer.toString(metadata.year()) : "";
                case "isbn" -> metadata.isbn();
                default -> "";
            };
            if (!blank(value)) { simpleElement(writer, scan, field, value); emitted.add(field); }
        }
    }

    private void writeGeneratedDescription(XMLStreamWriter writer, Scan scan, CanonicalBookMetadata metadata,
                                           CoverPlan cover, Set<String> emittedTitle, Set<String> emittedPublish)
            throws XMLStreamException {
        startFb2(writer, scan, "description");
        writeTitleInfo(writer, scan, metadata, cover, emittedTitle);
        if (hasPublishMetadata(metadata)) writePublishInfo(writer, scan, metadata, emittedPublish);
        writer.writeEndElement();
    }

    private void writeTitleInfo(XMLStreamWriter writer, Scan scan, CanonicalBookMetadata metadata,
                                CoverPlan cover, Set<String> emitted) throws XMLStreamException {
        startFb2(writer, scan, "title-info");
        emitTitleBefore(writer, scan, metadata, cover, emitted, Integer.MAX_VALUE);
        writer.writeEndElement();
    }

    private void writePublishInfo(XMLStreamWriter writer, Scan scan, CanonicalBookMetadata metadata,
                                  Set<String> emitted) throws XMLStreamException {
        startFb2(writer, scan, "publish-info");
        emitPublishBefore(writer, scan, metadata, emitted, Integer.MAX_VALUE);
        writer.writeEndElement();
    }

    private void writePerson(XMLStreamWriter writer, Scan scan, String element,
                             CanonicalBookMetadata.CanonicalAuthor author) throws XMLStreamException {
        startFb2(writer, scan, element);
        if (!blank(author.firstName())) simpleElement(writer, scan, "first-name", author.firstName());
        if (!blank(author.middleName())) simpleElement(writer, scan, "middle-name", author.middleName());
        if (!blank(author.lastName())) simpleElement(writer, scan, "last-name", author.lastName());
        writer.writeEndElement();
    }

    private void writeTranslator(XMLStreamWriter writer, Scan scan, String value) throws XMLStreamException {
        startFb2(writer, scan, "translator");
        simpleElement(writer, scan, "nickname", value);
        writer.writeEndElement();
    }

    private void writeAnnotation(XMLStreamWriter writer, Scan scan, String value) throws XMLStreamException {
        startFb2(writer, scan, "annotation");
        simpleElement(writer, scan, "p", value);
        writer.writeEndElement();
    }

    private void writeSequence(XMLStreamWriter writer, Scan scan, String series, Integer number) throws XMLStreamException {
        startFb2(writer, scan, "sequence");
        writer.writeAttribute("name", series);
        if (number != null && number > 0) writer.writeAttribute("number", Integer.toString(number));
        writer.writeEndElement();
    }

    private void writeCoverPage(XMLStreamWriter writer, Scan scan, CoverPlan cover) throws XMLStreamException {
        startFb2(writer, scan, "coverpage");
        startFb2(writer, scan, "image");
        writer.writeNamespace("l", XLINK_NS);
        writer.writeAttribute("l", XLINK_NS, "href", "#" + cover.id());
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private void writeCoverBinary(XMLStreamWriter writer, Scan scan, CoverPlan cover, BooleanSupplier cancelled) throws XMLStreamException {
        startFb2(writer, scan, "binary");
        writer.writeAttribute("id", cover.id());
        writer.writeAttribute("content-type", cover.mimeType());
        writeBase64Characters(writer, cover.cover(), cancelled);
        writer.writeEndElement();
    }

    /** Writes Base64 in independently encodable 48 KiB blocks to avoid allocating one large encoded String. */
    private void writeBase64Characters(XMLStreamWriter writer, CanonicalBookMetadata.CanonicalCover cover,
                                       BooleanSupplier cancelled) throws XMLStreamException {
        if (cover == null || cover.isEmpty()) return;
        final int chunkBytes = 48 * 1024; // divisible by 3, so only the final chunk can contain padding
        Base64.Encoder encoder = Base64.getEncoder();
        int total = cover.byteSize();
        for (int offset = 0; offset < total; offset += chunkBytes) {
            checkCancelled(cancelled);
            int end = Math.min(total, offset + chunkBytes);
            byte[] encoded = encoder.encode(cover.copyRange(offset, end));
            writer.writeCharacters(new String(encoded, StandardCharsets.US_ASCII));
        }
    }

    private void simpleElement(XMLStreamWriter writer, Scan scan, String local, String value) throws XMLStreamException {
        startFb2(writer, scan, local);
        writer.writeCharacters(value);
        writer.writeEndElement();
    }

    private void startFb2(XMLStreamWriter writer, Scan scan, String local) throws XMLStreamException {
        if (scan.rootNamespace().isBlank()) writer.writeStartElement(local);
        else writer.writeStartElement(scan.rootPrefix(), local, scan.rootNamespace());
    }

    private void copyStartElement(XMLStreamReader reader, XMLStreamWriter writer,
                                  Map<String, String> noNamespaceAttributeOverrides) throws XMLStreamException {
        Map<String, String> overrides = noNamespaceAttributeOverrides == null ? Map.of() : noNamespaceAttributeOverrides;
        String ns = text(reader.getNamespaceURI());
        String prefix = text(reader.getPrefix());
        if (ns.isBlank()) writer.writeStartElement(reader.getLocalName());
        else writer.writeStartElement(prefix, reader.getLocalName(), ns);
        for (int i = 0; i < reader.getNamespaceCount(); i++) {
            String p = reader.getNamespacePrefix(i);
            String uri = text(reader.getNamespaceURI(i));
            if (p == null || p.isBlank()) writer.writeDefaultNamespace(uri);
            else writer.writeNamespace(p, uri);
        }
        Set<String> writtenOverrides = new HashSet<>();
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String ans = text(reader.getAttributeNamespace(i));
            String ap = text(reader.getAttributePrefix(i));
            String local = reader.getAttributeLocalName(i);
            String value = reader.getAttributeValue(i);
            if (ans.isBlank() && overrides.containsKey(local)) {
                writer.writeAttribute(local, overrides.get(local));
                writtenOverrides.add(local);
            } else if (ans.isBlank()) writer.writeAttribute(local, value);
            else writer.writeAttribute(ap, ans, local, value);
        }
        for (Map.Entry<String, String> override : overrides.entrySet()) {
            if (!writtenOverrides.contains(override.getKey())) writer.writeAttribute(override.getKey(), override.getValue());
        }
    }

    private void fillEmptyGenre(XMLStreamWriter writer, Scan scan, CanonicalBookMetadata metadata, int emptyIndex)
            throws XMLStreamException {
        List<String> missing = missingGenres(scan, metadata);
        if (emptyIndex >= 0 && emptyIndex < missing.size()) writer.writeCharacters(missing.get(emptyIndex));
    }

    private void fillEmptyAuthor(XMLStreamWriter writer, Scan scan, CanonicalBookMetadata metadata, int emptyIndex)
            throws XMLStreamException {
        List<CanonicalBookMetadata.CanonicalAuthor> missing = missingAuthors(scan, metadata);
        if (emptyIndex < 0 || emptyIndex >= missing.size()) return;
        CanonicalBookMetadata.CanonicalAuthor author = missing.get(emptyIndex);
        if (!blank(author.firstName())) simpleElement(writer, scan, "first-name", author.firstName());
        if (!blank(author.middleName())) simpleElement(writer, scan, "middle-name", author.middleName());
        if (!blank(author.lastName())) simpleElement(writer, scan, "last-name", author.lastName());
    }

    private List<String> missingGenres(Scan scan, CanonicalBookMetadata metadata) {
        List<String> result = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>(scan.existingGenres());
        for (String genre : metadata.genres()) {
            String key = identityKey(genre);
            if (!key.isBlank() && seen.add(key)) result.add(genre);
        }
        return List.copyOf(result);
    }

    private List<CanonicalBookMetadata.CanonicalAuthor> missingAuthors(Scan scan, CanonicalBookMetadata metadata) {
        List<CanonicalBookMetadata.CanonicalAuthor> result = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>(scan.existingAuthors());
        for (CanonicalBookMetadata.CanonicalAuthor author : metadata.authors()) {
            if (author == null || author.isEmpty()) continue;
            String key = authorIdentityKey(author);
            if (!key.isBlank() && seen.add(key)) result.add(author);
        }
        return List.copyOf(result);
    }

    private void fillEmptyTitleField(XMLStreamWriter writer, Scan scan, CanonicalBookMetadata metadata,
                                     CoverPlan cover, String field) throws XMLStreamException {
        switch (field) {
            case "book-title" -> { if (!blank(metadata.title())) writer.writeCharacters(metadata.title()); }
            case "annotation" -> { if (!blank(metadata.annotation())) simpleElement(writer, scan, "p", metadata.annotation()); }
            case "coverpage" -> { if (cover.required()) {
                startFb2(writer, scan, "image");
                writer.writeNamespace("l", XLINK_NS);
                writer.writeAttribute("l", XLINK_NS, "href", "#" + cover.id());
                writer.writeEndElement();
            } }
            case "lang" -> { if (!blank(metadata.language())) writer.writeCharacters(metadata.language()); }
            case "translator" -> { if (!blank(metadata.translators())) simpleElement(writer, scan, "nickname", metadata.translators()); }
            // sequence attributes are supplemented immediately after its start tag.
            default -> { }
        }
    }

    private void fillEmptyPublishField(XMLStreamWriter writer, Scan scan, CanonicalBookMetadata metadata,
                                       String field) throws XMLStreamException {
        String value = switch (field) {
            case "publisher" -> metadata.publisher();
            case "year" -> metadata.year() != null && metadata.year() > 0 ? Integer.toString(metadata.year()) : "";
            case "isbn" -> metadata.isbn();
            default -> "";
        };
        if (!blank(value)) writer.writeCharacters(value);
    }

    private static boolean elementHasValue(XMLStreamReader reader) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String value = reader.getAttributeValue(i);
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }

    private static void appendIdentityText(StringBuilder target, String value) {
        if (value == null || value.isEmpty() || target.length() >= 65_536) return;
        int remaining = 65_536 - target.length();
        target.append(value, 0, Math.min(value.length(), remaining));
    }

    private static void appendIdentitySeparator(StringBuilder target) {
        if (!target.isEmpty() && target.length() < 65_536
                && !Character.isWhitespace(target.charAt(target.length() - 1))) target.append(' ');
    }

    private static String authorIdentityKey(CanonicalBookMetadata.CanonicalAuthor author) {
        if (author == null) return "";
        return identityKey(String.join(" ", author.firstName(), author.middleName(), author.lastName()));
    }

    private static String identityKey(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replace('\u00A0', ' ').strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean()))
            throw new java.util.concurrent.CancellationException("FB2 metadata merge cancelled");
    }

    private boolean needsChanges(Scan scan, CanonicalBookMetadata metadata, CoverPlan cover) {
        if (!scan.description() || !scan.titleInfo()) return hasTitleMetadata(metadata, cover) || hasPublishMetadata(metadata);
        if (cover.required()) return true;
        if (metadata.genres().stream().filter(g -> !blank(g))
                .anyMatch(g -> !scan.existingGenres().contains(identityKey(g)))) return true;
        if (metadata.authors().stream().filter(a -> a != null && !a.isEmpty())
                .anyMatch(a -> !scan.existingAuthors().contains(authorIdentityKey(a)))) return true;
        if (!scan.titleFieldsWithValue().contains("book-title") && !blank(metadata.title())) return true;
        if (!scan.titleFieldsWithValue().contains("annotation") && !blank(metadata.annotation())) return true;
        if (!scan.titleFieldsWithValue().contains("lang") && !blank(metadata.language())) return true;
        if (!scan.titleFieldsWithValue().contains("translator") && !blank(metadata.translators())) return true;
        if (scan.sequenceCount() == 0 && !blank(metadata.series())) return true;
        if (scan.sequenceCount() == 1 && !scan.sequenceNameWithValue() && !blank(metadata.series())) return true;
        if (scan.sequenceCount() == 1 && !scan.sequenceNumberWithValue()
                && metadata.sequenceNumber() != null && metadata.sequenceNumber() > 0) return true;
        if (hasPublishMetadata(metadata)) {
            if (!scan.publishInfo()) return true;
            if (!scan.publishFieldsWithValue().contains("publisher") && !blank(metadata.publisher())) return true;
            if (!scan.publishFieldsWithValue().contains("year") && metadata.year() != null && metadata.year() > 0) return true;
            if (!scan.publishFieldsWithValue().contains("isbn") && !blank(metadata.isbn())) return true;
        }
        return false;
    }

    private boolean hasTitleMetadata(CanonicalBookMetadata metadata, CoverPlan cover) {
        return !blank(metadata.title()) || !metadata.genres().isEmpty() || !metadata.authors().isEmpty()
                || !blank(metadata.annotation()) || !blank(metadata.language()) || !blank(metadata.translators())
                || !blank(metadata.series()) || cover.required();
    }

    private boolean hasPublishMetadata(CanonicalBookMetadata metadata) {
        return !blank(metadata.publisher()) || (metadata.year() != null && metadata.year() > 0) || !blank(metadata.isbn());
    }

    private CoverPlan coverPlan(CanonicalBookMetadata.CanonicalCover cover, Set<String> existingIds,
                                boolean existingCoverPage) {
        if (existingCoverPage || cover == null || cover.isEmpty()) return CoverPlan.none();
        String mime = normalizeCoverMime(cover.mimeType());
        if (mime.isBlank()) return CoverPlan.none();
        String extension = switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            default -> ".img";
        };
        int index = 1;
        String id;
        do {
            id = index == 1 ? "myhomelib-cover" + extension : "myhomelib-cover-" + index + extension;
            index++;
        } while (existingIds.contains(id));
        return new CoverPlan(true, id, mime, cover);
    }

    private static XMLInputFactory secureInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        requireInputProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        requireInputProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        requireInputProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory;
    }

    private static void requireInputProperty(XMLInputFactory factory, String key, Object value) {
        try {
            factory.setProperty(key, value);
            Object actual = factory.getProperty(key);
            if (actual instanceof Boolean actualBoolean && value instanceof Boolean requestedBoolean
                    && actualBoolean.booleanValue() != requestedBoolean.booleanValue()) {
                throw new IllegalStateException("XML parser ignored required security property " + key);
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("XML parser does not support required security property " + key, failure);
        }
    }

    private static void trySetOutputProperty(XMLOutputFactory factory, String key, Object value) {
        try { factory.setProperty(key, value); } catch (IllegalArgumentException ignored) { }
    }

    private static String normalizeCoverMime(String value) {
        String mime = text(value).strip().toLowerCase(Locale.ROOT);
        return switch (mime) {
            case "image/jpeg", "image/png", "image/gif" -> mime;
            default -> "";
        };
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String text(String value) { return value == null ? "" : value; }

    private static Map<String, Integer> publishInfoOrder() {
        Map<String, Integer> order = new HashMap<>();
        order.put("book-name", 10);
        order.put("publisher", 20);
        order.put("city", 30);
        order.put("year", 40);
        order.put("isbn", 50);
        order.put("sequence", 60);
        return Map.copyOf(order);
    }

    private static Map<String, Integer> titleInfoOrder() {
        Map<String, Integer> order = new HashMap<>();
        order.put("genre", 10);
        order.put("author", 20);
        order.put("book-title", 30);
        order.put("annotation", 40);
        order.put("keywords", 50);
        order.put("date", 60);
        order.put("coverpage", 70);
        order.put("lang", 80);
        order.put("src-lang", 90);
        order.put("translator", 100);
        order.put("sequence", 110);
        return Map.copyOf(order);
    }

    private record Scan(boolean description, boolean titleInfo, boolean publishInfo,
                        Set<String> titleFields, Set<String> titleFieldsWithValue,
                        Set<String> publishFields, Set<String> publishFieldsWithValue, Set<String> binaryIds,
                        Set<String> existingGenres, Set<String> existingAuthors,
                        int emptyGenreCount, int emptyAuthorCount, int sequenceCount,
                        boolean sequenceNameWithValue, boolean sequenceNumberWithValue,
                        String rootNamespace, String rootPrefix) { }

    private record CoverPlan(boolean required, String id, String mimeType, CanonicalBookMetadata.CanonicalCover cover) {
        private CoverPlan {
            cover = cover == null ? new CanonicalBookMetadata.CanonicalCover("", new byte[0]) : cover;
        }
        static CoverPlan none() { return new CoverPlan(false, "", "", new CanonicalBookMetadata.CanonicalCover("", new byte[0])); }
    }
}
