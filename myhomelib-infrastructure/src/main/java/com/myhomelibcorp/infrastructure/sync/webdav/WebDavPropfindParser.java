package com.myhomelibcorp.infrastructure.sync.webdav;

import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** Hardened minimal parser for DAV:href values returned by Depth:1 PROPFIND. */
final class WebDavPropfindParser {
    private static final int MAX_HREFS = 100_000;
    private static final int MAX_HREF_CHARS = 4096;

    List<String> hrefs(byte[] xml) {
        if (xml == null || xml.length == 0) return List.of();
        XMLInputFactory factory = SecureXmlInputFactory.create(false, false);
        List<String> result = new ArrayList<>();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) throw new IllegalArgumentException("DTD is not allowed in WebDAV response");
                if (event == XMLStreamConstants.START_ELEMENT && "href".equals(reader.getLocalName())) {
                    String value = reader.getElementText();
                    if (value.length() > MAX_HREF_CHARS) throw new IllegalArgumentException("WebDAV href is too long");
                    result.add(value);
                    if (result.size() > MAX_HREFS) throw new IllegalArgumentException("Too many WebDAV href entries");
                }
            }
            reader.close();
            return List.copyOf(result);
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Invalid WebDAV PROPFIND response", e);
        }
    }

}
