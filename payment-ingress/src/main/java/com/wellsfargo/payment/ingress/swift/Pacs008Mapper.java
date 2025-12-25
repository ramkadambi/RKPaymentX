package com.wellsfargo.payment.ingress.swift;

import com.wellsfargo.payment.canonical.Agent;
import com.wellsfargo.payment.canonical.Party;
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
 * Mapper for converting ISO 20022 PACS.008 (Customer Credit Transfer) XML messages
 * to canonical PaymentEvent.
 * 
 * Extracts only routing-relevant fields:
 * - EndToEndId
 * - IntrBkSttlmAmt or InstdAmt (currency, amount)
 * - DbtrAgt BIC
 * - IntrmyAgt BIC (if present)
 * - CdtrAgt BIC
 * 
 * This mapper is rail-agnostic and does not include any rail-specific fields
 * in the canonical model. All routing decisions are made later by the routing engine.
 */
public class Pacs008Mapper {
    
    private static final Logger log = LoggerFactory.getLogger(Pacs008Mapper.class);
    
    // PACS.008 element paths
    private static final String FITOFI_CSTM_CDT_TRF = "FIToFICstmrCdtTrf";
    private static final String GRP_HDR = "GrpHdr";
    private static final String MSG_ID = "MsgId";
    private static final String CDT_TRF_TX_INF = "CdtTrfTxInf";
    private static final String PMT_ID = "PmtId";
    private static final String END_TO_END_ID = "EndToEndId";
    private static final String AMT = "Amt";
    private static final String INTR_BK_STTLM_AMT = "IntrBkSttlmAmt";
    private static final String INSTD_AMT = "InstdAmt";
    private static final String DBTR_AGT = "DbtrAgt";
    private static final String INTRMY_AGT = "IntrmyAgt";
    private static final String CDTR_AGT = "CdtrAgt";
    private static final String FIN_INSTN_ID = "FinInstnId";
    private static final String BIC = "BIC";
    private static final String BICFI = "BICFI";
    private static final String DBTR = "Dbtr";
    private static final String CDTR = "Cdtr";
    private static final String NM = "Nm";
    private static final String RMT_INF = "RmtInf";
    private static final String USTRD = "Ustrd";
    
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
     * Maps a PACS.008 XML string to a canonical PaymentEvent.
     * 
     * @param xmlMessage The PACS.008 XML message as a string
     * @param direction Payment direction (INBOUND, OUTBOUND, or INTERNAL)
     * @return PaymentEvent with routing-relevant fields populated
     * @throws Pacs008MappingException if mapping fails
     */
    public static PaymentEvent mapToPaymentEvent(String xmlMessage, PaymentDirection direction) 
            throws Pacs008MappingException {
        if (xmlMessage == null || xmlMessage.trim().isEmpty()) {
            throw new Pacs008MappingException("XML message cannot be null or empty");
        }
        
        try (InputStream inputStream = new ByteArrayInputStream(
                xmlMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return mapToPaymentEventFromStream(inputStream, xmlMessage, direction);
        } catch (IOException e) {
            throw new Pacs008MappingException("Failed to read XML message", e);
        }
    }
    
    /**
     * Maps a PACS.008 XML input stream to a canonical PaymentEvent.
     */
    private static PaymentEvent mapToPaymentEventFromStream(
            InputStream inputStream, 
            String rawMessage,
            PaymentDirection direction) 
            throws Pacs008MappingException {
        
        try {
            DocumentBuilder documentBuilder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
            Document document = documentBuilder.parse(inputStream);
            
            Element rootElement = document.getDocumentElement();
            if (rootElement == null) {
                throw new Pacs008MappingException("XML document has no root element");
            }
            
            // Find FIToFICstmrCdtTrf element (may be root or child of Document)
            Element fitofiElement = findElement(rootElement, FITOFI_CSTM_CDT_TRF);
            if (fitofiElement == null) {
                throw new Pacs008MappingException("PACS.008 message must contain FIToFICstmrCdtTrf element");
            }
            
            // Extract message ID from GrpHdr
            String msgId = extractMsgId(fitofiElement);
            
            // Extract transaction information (assume first CdtTrfTxInf)
            Element cdtTrfTxInf = findElement(fitofiElement, CDT_TRF_TX_INF);
            if (cdtTrfTxInf == null) {
                throw new Pacs008MappingException("PACS.008 message must contain CdtTrfTxInf element");
            }
            
            // Extract EndToEndId
            String endToEndId = extractEndToEndId(cdtTrfTxInf);
            if (endToEndId == null || endToEndId.isEmpty()) {
                throw new Pacs008MappingException("PACS.008 message must contain EndToEndId");
            }
            
            // Extract amount and currency
            AmountInfo amountInfo = extractAmount(cdtTrfTxInf);
            if (amountInfo == null) {
                throw new Pacs008MappingException("PACS.008 message must contain amount information");
            }
            
            // Extract agents
            Agent debtorAgent = extractDebtorAgent(cdtTrfTxInf);
            if (debtorAgent == null) {
                throw new Pacs008MappingException("PACS.008 message must contain DbtrAgt");
            }
            
            Agent creditorAgent = extractCreditorAgent(cdtTrfTxInf);
            if (creditorAgent == null) {
                throw new Pacs008MappingException("PACS.008 message must contain CdtrAgt");
            }
            
            // Extract intermediary agent (optional)
            Agent intermediaryAgent = extractIntermediaryAgent(cdtTrfTxInf);
            
            // Extract parties (optional - for customer credit transfers)
            Party debtor = extractDebtor(cdtTrfTxInf);
            Party creditor = extractCreditor(cdtTrfTxInf);
            
            // Extract remittance information (optional)
            String remittanceInfo = extractRemittanceInfo(cdtTrfTxInf);
            
            // Build PaymentEvent
            var eventBuilder = PaymentEvent.builder()
                .msgId(msgId != null ? msgId : "PACS008-" + endToEndId)
                .endToEndId(endToEndId)
                .sourceMessageType(MessageSource.ISO20022_PACS008)
                .sourceMessageRaw(rawMessage)
                .direction(direction)
                .status(PaymentStatus.RECEIVED)
                .amount(amountInfo.amount)
                .currency(amountInfo.currency)
                .debtorAgent(debtorAgent)
                .creditorAgent(creditorAgent)
                .debtor(debtor)
                .creditor(creditor)
                .remittanceInfo(remittanceInfo)
                .createdTimestamp(Instant.now().toString())
                .lastUpdatedTimestamp(Instant.now().toString());
            
            // Note: Intermediary agent is not directly part of PaymentEvent canonical model.
            // If needed for routing, it should be added to routingContext during enrichment.
            // For now, we log it for reference.
            if (intermediaryAgent != null) {
                log.debug("Intermediary agent found in PACS.008: {}={}", 
                    intermediaryAgent.getIdScheme(), intermediaryAgent.getIdValue());
            }
            
            return eventBuilder.build();
            
        } catch (ParserConfigurationException e) {
            throw new Pacs008MappingException("Failed to configure XML parser", e);
        } catch (SAXException e) {
            throw new Pacs008MappingException("Failed to parse XML message", e);
        } catch (IOException e) {
            throw new Pacs008MappingException("Failed to read XML message", e);
        }
    }
    
    /**
     * Extract message ID from GrpHdr.
     */
    private static String extractMsgId(Element fitofiElement) {
        Element grpHdr = findElement(fitofiElement, GRP_HDR);
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
     * Extract amount and currency. Checks IntrBkSttlmAmt first, then InstdAmt.
     */
    private static AmountInfo extractAmount(Element cdtTrfTxInf) {
        // First try IntrBkSttlmAmt (interbank settlement amount)
        Element intrBkSttlmAmt = findElement(cdtTrfTxInf, INTR_BK_STTLM_AMT);
        if (intrBkSttlmAmt != null) {
            String amountStr = getTextContent(intrBkSttlmAmt);
            String currency = intrBkSttlmAmt.getAttribute("Ccy");
            if (amountStr != null && !amountStr.isEmpty() && currency != null && !currency.isEmpty()) {
                try {
                    return new AmountInfo(new BigDecimal(amountStr.trim()), currency.trim());
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse IntrBkSttlmAmt: {}", amountStr, e);
                }
            }
        }
        
        // Fall back to InstdAmt (instructed amount) - found in Amt/InstdAmt
        Element amt = findElement(cdtTrfTxInf, AMT);
        if (amt != null) {
            Element instdAmt = findElement(amt, INSTD_AMT);
            if (instdAmt != null) {
                String amountStr = getTextContent(instdAmt);
                String currency = instdAmt.getAttribute("Ccy");
                if (amountStr != null && !amountStr.isEmpty() && currency != null && !currency.isEmpty()) {
                    try {
                        return new AmountInfo(new BigDecimal(amountStr.trim()), currency.trim());
                    } catch (NumberFormatException e) {
                        log.warn("Failed to parse InstdAmt: {}", amountStr, e);
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Extract debtor agent (DbtrAgt).
     */
    private static Agent extractDebtorAgent(Element cdtTrfTxInf) {
        Element dbtrAgt = findElement(cdtTrfTxInf, DBTR_AGT);
        if (dbtrAgt == null) {
            return null;
        }
        return extractAgentFromFinInstnId(dbtrAgt);
    }
    
    /**
     * Extract creditor agent (CdtrAgt).
     */
    private static Agent extractCreditorAgent(Element cdtTrfTxInf) {
        Element cdtrAgt = findElement(cdtTrfTxInf, CDTR_AGT);
        if (cdtrAgt == null) {
            return null;
        }
        return extractAgentFromFinInstnId(cdtrAgt);
    }
    
    /**
     * Extract intermediary agent (IntrmyAgt) - optional.
     */
    private static Agent extractIntermediaryAgent(Element cdtTrfTxInf) {
        Element intrmyAgt = findElement(cdtTrfTxInf, INTRMY_AGT);
        if (intrmyAgt == null) {
            return null;
        }
        return extractAgentFromFinInstnId(intrmyAgt);
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
        
        // Determine country from BIC (positions 4-6 are country code, but BIC format is 8 or 11 chars)
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
     * Extract debtor party (Dbtr) - optional.
     */
    private static Party extractDebtor(Element cdtTrfTxInf) {
        Element dbtr = findElement(cdtTrfTxInf, DBTR);
        if (dbtr == null) {
            return null;
        }
        
        String name = getTextContent(findElement(dbtr, NM));
        if (name == null || name.isEmpty()) {
            return null;
        }
        
        return Party.builder()
            .name(name.trim())
            .build();
    }
    
    /**
     * Extract creditor party (Cdtr) - optional.
     */
    private static Party extractCreditor(Element cdtTrfTxInf) {
        Element cdtr = findElement(cdtTrfTxInf, CDTR);
        if (cdtr == null) {
            return null;
        }
        
        String name = getTextContent(findElement(cdtr, NM));
        if (name == null || name.isEmpty()) {
            return null;
        }
        
        return Party.builder()
            .name(name.trim())
            .build();
    }
    
    /**
     * Extract remittance information (RmtInf/Ustrd) - optional.
     */
    private static String extractRemittanceInfo(Element cdtTrfTxInf) {
        Element rmtInf = findElement(cdtTrfTxInf, RMT_INF);
        if (rmtInf == null) {
            return null;
        }
        
        return getTextContent(findElement(rmtInf, USTRD));
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
     * Exception thrown when PACS.008 mapping fails.
     */
    public static class Pacs008MappingException extends Exception {
        public Pacs008MappingException(String message) {
            super(message);
        }
        
        public Pacs008MappingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

