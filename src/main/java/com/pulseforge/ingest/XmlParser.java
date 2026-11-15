package com.pulseforge.ingest;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Adaptee of the Adapter pattern: a thin wrapper over the JDK's streaming StAX parser.
 * It knows nothing about health data - it only streams elements and their attributes.
 * Streaming matters because real Apple Health exports are often hundreds of MB.
 */
public class XmlParser {

    private final XMLInputFactory factory;

    public XmlParser() {
        factory = XMLInputFactory.newInstance();
        // Hardening: never resolve external entities (XXE). Apple exports include an internal DTD,
        // which is simply skipped.
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    /**
     * Streams every element named {@code elementName}, passing its attributes to the consumer.
     *
     * @return the number of matching elements visited
     */
    public int forEachElement(Path file, String elementName, Consumer<Map<String, String>> consumer)
            throws IOException, XMLStreamException {
        int count = 0;
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && elementName.equals(reader.getLocalName())) {
                        Map<String, String> attrs = new HashMap<>();
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            attrs.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                        }
                        consumer.accept(attrs);
                        count++;
                    }
                }
            } finally {
                reader.close();
            }
        }
        return count;
    }
}
