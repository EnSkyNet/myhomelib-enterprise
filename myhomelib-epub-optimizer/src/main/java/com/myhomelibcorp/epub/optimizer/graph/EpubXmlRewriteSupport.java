package com.myhomelibcorp.epub.optimizer.graph;

import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Shared secure streaming XML copier used by conservative EPUB graph rewrites. */
final class EpubXmlRewriteSupport {
    private EpubXmlRewriteSupport() {}

    static byte[] rewrite(
            InputStream input,
            Predicate<String> removeManifestHref,
            BiFunction<String, String, String> attributeRewriter) throws Exception {
        Objects.requireNonNull(input, "input");
        Predicate<String> removal = removeManifestHref == null ? ignored -> false : removeManifestHref;
        BiFunction<String, String, String> rewrite = attributeRewriter == null ? (name, value) -> value : attributeRewriter;

        XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(input);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        XMLStreamWriter writer = XMLOutputFactory.newFactory()
                .createXMLStreamWriter(bytes, StandardCharsets.UTF_8.name());
        int manifestDepth = 0;
        int skipDepth = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (skipDepth > 0) {
                    if (event == XMLStreamConstants.START_ELEMENT) skipDepth++;
                    else if (event == XMLStreamConstants.END_ELEMENT) skipDepth--;
                    continue;
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("manifest".equals(local)) manifestDepth = 1;
                    else if (manifestDepth > 0) manifestDepth++;
                    if (manifestDepth > 1 && "item".equals(local)) {
                        String href = attribute(reader, "href");
                        if (href != null && removal.test(href)) {
                            skipDepth = 1;
                            manifestDepth--;
                            continue;
                        }
                    }
                    writeStart(reader, writer, rewrite);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    writer.writeEndElement();
                    if (manifestDepth > 0) manifestDepth--;
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.SPACE) {
                    writer.writeCharacters(reader.getText());
                } else if (event == XMLStreamConstants.CDATA) {
                    writer.writeCData(reader.getText());
                } else if (event == XMLStreamConstants.COMMENT) {
                    writer.writeComment(reader.getText());
                } else if (event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                    writer.writeProcessingInstruction(reader.getPITarget(), Objects.toString(reader.getPIData(), ""));
                }
            }
            writer.flush();
            return bytes.toByteArray();
        } finally {
            try {
                writer.close();
            } finally {
                reader.close();
            }
        }
    }

    private static void writeStart(
            XMLStreamReader reader,
            XMLStreamWriter writer,
            BiFunction<String, String, String> attributeRewriter) throws Exception {
        String namespace = Objects.toString(reader.getNamespaceURI(), "");
        String prefix = Objects.toString(reader.getPrefix(), "");
        if (namespace.isBlank()) writer.writeStartElement(reader.getLocalName());
        else writer.writeStartElement(prefix, reader.getLocalName(), namespace);

        for (int i = 0; i < reader.getNamespaceCount(); i++) {
            String nsPrefix = reader.getNamespacePrefix(i);
            String uri = Objects.toString(reader.getNamespaceURI(i), "");
            if (nsPrefix == null || nsPrefix.isBlank()) writer.writeDefaultNamespace(uri);
            else writer.writeNamespace(nsPrefix, uri);
        }
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String local = reader.getAttributeLocalName(i);
            String value = attributeRewriter.apply(local, reader.getAttributeValue(i));
            String attrNamespace = Objects.toString(reader.getAttributeNamespace(i), "");
            String attrPrefix = Objects.toString(reader.getAttributePrefix(i), "");
            if (attrNamespace.isBlank()) writer.writeAttribute(local, value);
            else writer.writeAttribute(attrPrefix, attrNamespace, local, value);
        }
    }

    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) return reader.getAttributeValue(i);
        }
        return null;
    }
}
