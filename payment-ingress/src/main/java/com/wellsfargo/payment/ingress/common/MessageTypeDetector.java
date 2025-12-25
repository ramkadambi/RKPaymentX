package com.wellsfargo.payment.ingress.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Utility class for detecting ISO 20022 message types from XML messages.
 * 
 * Detects message type based on schema elements:
 * - PACS_008: Contains FIToFICstmrCdtTrf element (may be root or first child of Document)
 * - PACS_009: Contains FICdtTrf element (may be root or first child of Document)
 * 
 * This is a pure utility class with no business logic or routing decisions.
 * It only inspects the XML structure to determine the message type.
 * 
 * Usage example:
 * <pre>{@code
 * try {
 *     Iso20022MessageType messageType = MessageTypeDetector.detect(xmlString);
 *     if (messageType == Iso20022MessageType.PACS_008) {
 *         // Handle customer credit transfer
 *     } else if (messageType == Iso20022MessageType.PACS_009) {
 *         // Handle financial institution credit transfer
 *     }
 * } catch (MessageTypeDetectionException e) {
 *     // Handle detection failure
 * }
 * }</pre>
 */
public class MessageTypeDetector {
    
    private static final Logger log = LoggerFactory.getLogger(MessageTypeDetector.class);
    
    // ISO 20022 root element names
    private static final String PACS_008_ROOT_ELEMENT = "FIToFICstmrCdtTrf";
    private static final String PACS_009_ROOT_ELEMENT = "FICdtTrf";
    
    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY;
    
    static {
        DOCUMENT_BUILDER_FACTORY = DocumentBuilderFactory.newInstance();
        DOCUMENT_BUILDER_FACTORY.setNamespaceAware(true);
        // Security: Disable external entity resolution to prevent XXE attacks
        try {
            DOCUMENT_BUILDER_FACTORY.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DOCUMENT_BUILDER_FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DOCUMENT_BUILDER_FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException e) {
            log.warn("Failed to configure XML parser security features", e);
        }
    }
    
    /**
     * Detects the ISO 20022 message type from an XML string.
     * 
     * @param xmlMessage The XML message as a string
     * @return Iso20022MessageType (PACS_008 or PACS_009)
     * @throws MessageTypeDetectionException if the message type cannot be determined
     */
    public static Iso20022MessageType detect(String xmlMessage) throws MessageTypeDetectionException {
        if (xmlMessage == null || xmlMessage.trim().isEmpty()) {
            throw new MessageTypeDetectionException("XML message cannot be null or empty");
        }
        
        try (InputStream inputStream = new ByteArrayInputStream(xmlMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return detectFromStream(inputStream);
        } catch (IOException e) {
            throw new MessageTypeDetectionException("Failed to read XML message", e);
        }
    }
    
    /**
     * Detects the ISO 20022 message type from an input stream.
     * 
     * @param inputStream The XML message as an input stream
     * @return Iso20022MessageType (PACS_008 or PACS_009)
     * @throws MessageTypeDetectionException if the message type cannot be determined
     */
    public static Iso20022MessageType detectFromStream(InputStream inputStream) throws MessageTypeDetectionException {
        if (inputStream == null) {
            throw new MessageTypeDetectionException("Input stream cannot be null");
        }
        
        try {
            DocumentBuilder documentBuilder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
            Document document = documentBuilder.parse(inputStream);
            
            Element rootElement = document.getDocumentElement();
            if (rootElement == null) {
                throw new MessageTypeDetectionException("XML document has no root element");
            }
            
            return detectFromRootElement(rootElement);
            
        } catch (ParserConfigurationException e) {
            throw new MessageTypeDetectionException("Failed to configure XML parser", e);
        } catch (SAXException e) {
            throw new MessageTypeDetectionException("Failed to parse XML message", e);
        } catch (IOException e) {
            throw new MessageTypeDetectionException("Failed to read XML message", e);
        }
    }
    
    /**
     * Detects the message type from the root element of a parsed XML document.
     * 
     * @param rootElement The root element of the XML document
     * @return Iso20022MessageType (PACS_008 or PACS_009)
     * @throws MessageTypeDetectionException if the message type cannot be determined
     */
    private static Iso20022MessageType detectFromRootElement(Element rootElement) throws MessageTypeDetectionException {
        // Get local name (without namespace prefix)
        String localName = rootElement.getLocalName();
        
        // Also check tag name in case namespace is not properly configured
        String tagName = rootElement.getTagName();
        
        // Remove namespace prefix if present (e.g., "pacs:FIToFICstmrCdtTrf" -> "FIToFICstmrCdtTrf")
        String elementName = localName != null && !localName.isEmpty() ? localName : tagName;
        if (elementName.contains(":")) {
            elementName = elementName.substring(elementName.indexOf(':') + 1);
        }
        
        log.debug("Detecting message type from root element: {}", elementName);
        
        // Check for PACS.008 (FIToFICstmrCdtTrf)
        if (PACS_008_ROOT_ELEMENT.equals(elementName)) {
            log.debug("Detected PACS.008 message type");
            return Iso20022MessageType.PACS_008;
        }
        
        // Check for PACS.009 (FICdtTrf)
        if (PACS_009_ROOT_ELEMENT.equals(elementName)) {
            log.debug("Detected PACS.009 message type");
            return Iso20022MessageType.PACS_009;
        }
        
        // If neither matches, check children elements
        // Sometimes the Document element wraps the actual message element
        Element child = getFirstChildElement(rootElement);
        if (child != null) {
            String childLocalName = child.getLocalName();
            String childTagName = child.getTagName();
            String childElementName = childLocalName != null && !childLocalName.isEmpty() ? childLocalName : childTagName;
            if (childElementName.contains(":")) {
                childElementName = childElementName.substring(childElementName.indexOf(':') + 1);
            }
            
            if (PACS_008_ROOT_ELEMENT.equals(childElementName)) {
                log.debug("Detected PACS.008 message type from child element");
                return Iso20022MessageType.PACS_008;
            }
            
            if (PACS_009_ROOT_ELEMENT.equals(childElementName)) {
                log.debug("Detected PACS.009 message type from child element");
                return Iso20022MessageType.PACS_009;
            }
        }
        
        throw new MessageTypeDetectionException(
            String.format("Unable to detect message type. Root element: '%s', Expected: '%s' or '%s'",
                elementName, PACS_008_ROOT_ELEMENT, PACS_009_ROOT_ELEMENT));
    }
    
    /**
     * Gets the first child element (skipping text nodes and other non-element nodes).
     */
    private static Element getFirstChildElement(Element parent) {
        org.w3c.dom.Node node = parent.getFirstChild();
        while (node != null) {
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                return (Element) node;
            }
            node = node.getNextSibling();
        }
        return null;
    }
    
    /**
     * Exception thrown when message type detection fails.
     */
    public static class MessageTypeDetectionException extends Exception {
        public MessageTypeDetectionException(String message) {
            super(message);
        }
        
        public MessageTypeDetectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

