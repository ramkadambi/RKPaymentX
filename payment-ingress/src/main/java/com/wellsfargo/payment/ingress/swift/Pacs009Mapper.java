package com.wellsfargo.payment.ingress.swift;

import com.wellsfargo.payment.canonical.Agent;
import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.MessageSource;
import com.wellsfargo.payment.canonical.enums.PaymentDirection;
import com.wellsfargo.payment.canonical.enums.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mapper for converting ISO 20022 PACS.009 (Financial Institution Credit Transfer) XML messages
 * to canonical PaymentEvent.
 * 
 * PACS.009 represents FI-to-FI settlement transactions used for:
 * - FED settlement legs
 * - CHIPS settlement legs
 * - SWIFT FI-to-FI transfers
 * 
 * Extracts only routing-relevant fields:
 * - EndToEndId or TxId (transaction identifier)
 * - IntrBkSttlmAmt (interbank settlement amount and currency)
 * - InstgAgt BIC (instructing agent - FI initiating the transfer)
 * - InstdAgt BIC (instructed agent - FI receiving the transfer)
 * - Falls back to DbtrAgt/CdtrAgt if InstgAgt/InstdAgt are not present
 * 
 * This mapper is rail-agnostic and does not include any rail-specific fields
 * in the canonical model. All routing decisions are made later by the routing engine.
 * The PaymentEvent is marked with sourceMessageType=ISO20022_PACS009 to indicate
 * it is a settlement transaction, which will be processed by the routing engine accordingly.
 */
public class Pacs009Mapper {
    
    private static final Logger log = LoggerFactory.getLogger(Pacs009Mapper.class);
    
    // PACS.009 element paths
    private static final String FI_CDT_TRF = "FICdtTrf";
    private static final String GRP_HDR = "GrpHdr";
    private static final String MSG_ID = "MsgId";
    private static final String TX_ID = "TxId";
    private static final String CDT_TRF_TX_INF = "CdtTrfTxInf";
    private static final String PMT_ID = "PmtId";
    private static final String INSTR_ID = "InstrId";
    private static final String END_TO_END_ID = "EndToEndId";
    private static final String INTR_BK_STTLM_AMT = "IntrBkSttlmAmt";
    private static final String INSTG_AGT = "InstgAgt";
    private static final String INSTD_AGT = "InstdAgt";
    private static final String DBTR_AGT = "DbtrAgt";
    private static final String CDTR_AGT = "CdtrAgt";
    private static final String FIN_INSTN_ID = "FinInstnId";
    private static final String BIC = "BIC";
    private static final String BICFI = "BICFI";
    
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
     * Maps a PACS.009 XML string to a canonical PaymentEvent.
     * 
     * @param xmlMessage The PACS.009 XML message as a string
     * @param direction Payment direction (INBOUND, OUTBOUND, or INTERNAL)
     * @return PaymentEvent with routing-relevant fields populated
     * @throws Pacs009MappingException if mapping fails
     */
    public static PaymentEvent mapToPaymentEvent(String xmlMessage, PaymentDirection direction) 
            throws Pacs009MappingException {
        if (xmlMessage == null || xmlMessage.trim().isEmpty()) {
            throw new Pacs009MappingException("XML message cannot be null or empty");
        }
        
        try (InputStream inputStream = new ByteArrayInputStream(
                xmlMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return mapToPaymentEventFromStream(inputStream, xmlMessage, direction);
        } catch (IOException e) {
            throw new Pacs009MappingException("Failed to read XML message", e);
        }
    }
    
    /**
     * Maps a PACS.009 XML input stream to a canonical PaymentEvent.
     */
    private static PaymentEvent mapToPaymentEventFromStream(
            InputStream inputStream, 
            String rawMessage,
            PaymentDirection direction) 
            throws Pacs009MappingException {
        
        try {
            DocumentBuilder documentBuilder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
            Document document = documentBuilder.parse(inputStream);
            
            Element rootElement = document.getDocumentElement();
            if (rootElement == null) {
                throw new Pacs009MappingException("XML document has no root element");
            }
            
            // Find FICdtTrf element (may be root or child of Document)
            Element ficdtTrfElement = findElement(rootElement, FI_CDT_TRF);
            if (ficdtTrfElement == null) {
                throw new Pacs009MappingException("PACS.009 message must contain FICdtTrf element");
            }
            
            // Extract message ID from GrpHdr
            String msgId = extractMsgId(ficdtTrfElement);
            
            // Extract transaction information (assume first CdtTrfTxInf)
            Element cdtTrfTxInf = findElement(ficdtTrfElement, CDT_TRF_TX_INF);
            if (cdtTrfTxInf == null) {
                throw new Pacs009MappingException("PACS.009 message must contain CdtTrfTxInf element");
            }
            
            // Extract EndToEndId or TxId (transaction identifier)
            String endToEndId = extractEndToEndId(cdtTrfTxInf);
            String txId = extractTxId(ficdtTrfElement, cdtTrfTxInf);
            
            // Use EndToEndId if present, otherwise use TxId
            String transactionId = (endToEndId != null && !endToEndId.isEmpty()) 
                ? endToEndId 
                : (txId != null && !txId.isEmpty() ? txId : null);
            
            if (transactionId == null) {
                throw new Pacs009MappingException("PACS.009 message must contain EndToEndId or TxId");
            }
            
            // Extract IntrBkSttlmAmt (required for FI-to-FI settlement)
            AmountInfo amountInfo = extractInterbankSettlementAmount(cdtTrfTxInf);
            if (amountInfo == null) {
                throw new Pacs009MappingException("PACS.009 message must contain IntrBkSttlmAmt");
            }
            
            // Extract agents - prioritize InstgAgt/InstdAgt, fall back to DbtrAgt/CdtrAgt
            Agent debtorAgent = extractInstructingAgent(cdtTrfTxInf);
            if (debtorAgent == null) {
                // Fall back to DbtrAgt
                debtorAgent = extractDebtorAgent(cdtTrfTxInf);
            }
            if (debtorAgent == null) {
                throw new Pacs009MappingException("PACS.009 message must contain InstgAgt or DbtrAgt");
            }
            
            Agent creditorAgent = extractInstructedAgent(cdtTrfTxInf);
            if (creditorAgent == null) {
                // Fall back to CdtrAgt
                creditorAgent = extractCreditorAgent(cdtTrfTxInf);
            }
            if (creditorAgent == null) {
                throw new Pacs009MappingException("PACS.009 message must contain InstdAgt or CdtrAgt");
            }
            
            // Build PaymentEvent
            var eventBuilder = PaymentEvent.builder()
                .msgId(msgId != null ? msgId : "PACS009-" + transactionId)
                .endToEndId(transactionId)
                .transactionId((txId != null && !txId.equals(transactionId)) ? txId : null) // Store TxId separately if different from EndToEndId
                .sourceMessageType(MessageSource.ISO20022_PACS009)
                .sourceMessageRaw(rawMessage)
                .direction(direction)
                .status(PaymentStatus.RECEIVED)
                .amount(amountInfo.amount)
                .currency(amountInfo.currency)
                .debtorAgent(debtorAgent)
                .creditorAgent(creditorAgent)
                .createdTimestamp(Instant.now().toString())
                .lastUpdatedTimestamp(Instant.now().toString());
            
            // Note: RoutingContext is NOT set here - it is added by the routing validation satellite
            // The sourceMessageType=ISO20022_PACS009 indicates this is a settlement transaction,
            // which the routing engine will use to determine appropriate routing rules.
            
            return eventBuilder.build();
            
        } catch (ParserConfigurationException e) {
            throw new Pacs009MappingException("Failed to configure XML parser", e);
        } catch (SAXException e) {
            throw new Pacs009MappingException("Failed to parse XML message", e);
        } catch (IOException e) {
            throw new Pacs009MappingException("Failed to read XML message", e);
        }
    }
    
    /**
     * Extract message ID from GrpHdr.
     */
    private static String extractMsgId(Element ficdtTrfElement) {
        Element grpHdr = findElement(ficdtTrfElement, GRP_HDR);
        if (grpHdr == null) {
            return null;
        }
        return getTextContent(findElement(grpHdr, MSG_ID));
    }
    
    /**
     * Extract EndToEndId from PmtId.
     */
    private static String extractEndToEndId(Element cdtTrfTxInf) {
        Element pmtId = findElement(cdtTrfTxInf, PMT_ID);
        if (pmtId == null) {
            return null;
        }
        return getTextContent(findElement(pmtId, END_TO_END_ID));
    }
    
    /**
     * Extract TxId (transaction ID) - may be in GrpHdr or CdtTrfTxInf.
     */
    private static String extractTxId(Element ficdtTrfElement, Element cdtTrfTxInf) {
        // First check in CdtTrfTxInf
        String txId = getTextContent(findElement(cdtTrfTxInf, TX_ID));
        if (txId != null && !txId.isEmpty()) {
            return txId;
        }
        
        // Check in GrpHdr
        Element grpHdr = findElement(ficdtTrfElement, GRP_HDR);
        if (grpHdr != null) {
            txId = getTextContent(findElement(grpHdr, TX_ID));
            if (txId != null && !txId.isEmpty()) {
                return txId;
            }
        }
        
        // As fallback, use InstrId if available
        Element pmtId = findElement(cdtTrfTxInf, PMT_ID);
        if (pmtId != null) {
            return getTextContent(findElement(pmtId, INSTR_ID));
        }
        
        return null;
    }
    
    /**
     * Extract IntrBkSttlmAmt (interbank settlement amount and currency).
     */
    private static AmountInfo extractInterbankSettlementAmount(Element cdtTrfTxInf) {
        Element intrBkSttlmAmt = findElement(cdtTrfTxInf, INTR_BK_STTLM_AMT);
        if (intrBkSttlmAmt == null) {
            return null;
        }
        
        String amountStr = getTextContent(intrBkSttlmAmt);
        String currency = intrBkSttlmAmt.getAttribute("Ccy");
        
        if (amountStr == null || amountStr.isEmpty()) {
            return null;
        }
        
        if (currency == null || currency.isEmpty()) {
            log.warn("IntrBkSttlmAmt missing currency attribute");
            return null;
        }
        
        try {
            return new AmountInfo(new BigDecimal(amountStr.trim()), currency.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse IntrBkSttlmAmt: {}", amountStr, e);
            return null;
        }
    }
    
    /**
     * Extract instructing agent (InstgAgt) - FI initiating the transfer.
     */
    private static Agent extractInstructingAgent(Element cdtTrfTxInf) {
        Element instgAgt = findElement(cdtTrfTxInf, INSTG_AGT);
        if (instgAgt == null) {
            return null;
        }
        return extractAgentFromFinInstnId(instgAgt);
    }
    
    /**
     * Extract instructed agent (InstdAgt) - FI receiving the transfer.
     */
    private static Agent extractInstructedAgent(Element cdtTrfTxInf) {
        Element instdAgt = findElement(cdtTrfTxInf, INSTD_AGT);
        if (instdAgt == null) {
            return null;
        }
        return extractAgentFromFinInstnId(instdAgt);
    }
    
    /**
     * Extract debtor agent (DbtrAgt) - fallback if InstgAgt is not present.
     */
    private static Agent extractDebtorAgent(Element cdtTrfTxInf) {
        Element dbtrAgt = findElement(cdtTrfTxInf, DBTR_AGT);
        if (dbtrAgt == null) {
            return null;
        }
        return extractAgentFromFinInstnId(dbtrAgt);
    }
    
    /**
     * Extract creditor agent (CdtrAgt) - fallback if InstdAgt is not present.
     */
    private static Agent extractCreditorAgent(Element cdtTrfTxInf) {
        Element cdtrAgt = findElement(cdtTrfTxInf, CDTR_AGT);
        if (cdtrAgt == null) {
            return null;
        }
        return extractAgentFromFinInstnId(cdtrAgt);
    }
    
    /**
     * Extract agent from FinInstnId element. Supports both BIC and BICFI elements.
     */
    private static Agent extractAgentFromFinInstnId(Element agentElement) {
        Element finInstnId = findElement(agentElement, FIN_INSTN_ID);
        if (finInstnId == null) {
            return null;
        }
        
        // Try BICFI first (newer ISO 20022 format)
        String bic = getTextContent(findElement(finInstnId, BICFI));
        if (bic == null || bic.isEmpty()) {
            // Fall back to BIC (older format)
            bic = getTextContent(findElement(finInstnId, BIC));
        }
        
        if (bic == null || bic.isEmpty()) {
            return null;
        }
        
        // Determine country from BIC (positions 4-6 are country code)
        // BIC format: 4 letters (bank code) + 2 letters (country) + 2 alphanumeric (location) + optional 3 (branch)
        String country = null;
        if (bic.length() >= 6) {
            country = bic.substring(4, 6);
        }
        
        return Agent.builder()
            .idScheme("BIC")
            .idValue(bic.trim())
            .country(country)
            .build();
    }
    
    /**
     * Find an element by local name (namespace-aware search).
     * Searches both direct children and recursively if needed.
     */
    private static Element findElement(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        
        // Check direct children first
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                String elementLocalName = element.getLocalName();
                String elementTagName = element.getTagName();
                
                // Check local name (without namespace) or tag name (with namespace prefix)
                if (localName.equals(elementLocalName) || 
                    localName.equals(elementTagName) ||
                    elementTagName.endsWith(":" + localName)) {
                    return element;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Get text content from an element, handling whitespace.
     */
    private static String getTextContent(Element element) {
        if (element == null) {
            return null;
        }
        
        String text = element.getTextContent();
        return text != null ? text.trim() : null;
    }
    
    /**
     * Helper class to hold amount and currency information.
     */
    private static class AmountInfo {
        final BigDecimal amount;
        final String currency;
        
        AmountInfo(BigDecimal amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }
    }
    
    /**
     * Exception thrown when PACS.009 mapping fails.
     */
    public static class Pacs009MappingException extends Exception {
        public Pacs009MappingException(String message) {
            super(message);
        }
        
        public Pacs009MappingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

