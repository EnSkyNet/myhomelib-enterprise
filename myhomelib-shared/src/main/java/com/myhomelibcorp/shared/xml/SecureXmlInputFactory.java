package com.myhomelibcorp.shared.xml;

import javax.xml.stream.XMLInputFactory;

/**
 * Fail-closed StAX factory for untrusted book/catalog XML.
 *
 * <p>MyHomeLib parses FB2, EPUB package files and other XML supplied by users or
 * remote catalogues. Every parser must therefore reject DTD processing and external
 * entities. Keeping this policy in one place avoids a security regression when a
 * new parser is added or an existing parser is refactored.</p>
 */
public final class SecureXmlInputFactory {
    private SecureXmlInputFactory() { }

    public static XMLInputFactory create() {
        return create(false, false);
    }

    public static XMLInputFactory create(boolean coalescing) {
        return create(coalescing, false);
    }

    public static XMLInputFactory create(boolean coalescing, boolean replaceEntityReferences) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        require(factory, XMLInputFactory.SUPPORT_DTD, false);
        require(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        require(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, replaceEntityReferences);
        require(factory, XMLInputFactory.IS_COALESCING, coalescing);
        return factory;
    }

    private static void require(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
            Object actual = factory.getProperty(property);
            if (actual instanceof Boolean expected && value instanceof Boolean requested
                    && expected.booleanValue() != requested.booleanValue()) {
                throw new IllegalStateException("StAX provider ignored required property " + property);
            }
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalStateException(
                    "StAX provider does not support required secure property " + property, unsupported);
        }
    }
}
