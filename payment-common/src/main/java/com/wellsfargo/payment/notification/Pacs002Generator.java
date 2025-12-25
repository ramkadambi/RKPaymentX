package com.wellsfargo.payment.notification;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.Pacs002Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Generator for PACS.002 status report messages.
 * 
 * PACS.002 is used to send payment status updates to the instructing bank
 * via SWIFT. This generator creates ISO 20022 compliant XML messages.
 */
public class Pacs002Generator {
    
    private static final Logger log = LoggerFactory.getLogger(Pacs002Generator.class);
    private static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    
    /**
     * Generate PACS.002 status report XML from PaymentEvent and status.
     * 
     * @param event Original PaymentEvent
     * @param status PACS.002 status code
     * @param reasonCode Optional reason code (e.g., "ACSP", "RJCT", "CANC")
     * @param additionalInfo Optional additional information
     * @return PACS.002 XML message
     */
    public String generatePacs002(PaymentEvent event, Pacs002Status status, 
                                   String reasonCode, String additionalInfo) {
        try {
            String msgId = generateMessageId(event);
            String creDtTm = Instant.now().toString();
            String originalMsgId = event.getMsgId() != null ? event.getMsgId() : "";
            String originalMsgNmId = getOriginalMessageNameId(event);
            String originalInstrId = event.getEndToEndId() != null ? event.getEndToEndId() : "";
            String originalEndToEndId = event.getEndToEndId() != null ? event.getEndToEndId() : "";
            String originalCtrlSum = event.getAmount() != null ? event.getAmount().toString() : "0.00";
            
            // Default reason code to status if not provided
            if (reasonCode == null || reasonCode.isEmpty()) {
                reasonCode = status.getValue();
            }
            
            // Default additional info if not provided
            if (additionalInfo == null || additionalInfo.isEmpty()) {
                additionalInfo = getDefaultAdditionalInfo(status);
            }
            
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<Document xmlns=\"").append(NAMESPACE).append("\">\n");
            xml.append("  <FIToFIPmtStsRpt>\n");
            
            // Group Header
            xml.append("    <GrpHdr>\n");
            xml.append("      <MsgId>").append(escapeXml(msgId)).append("</MsgId>\n");
            xml.append("      <CreDtTm>").append(escapeXml(creDtTm)).append("</CreDtTm>\n");
            xml.append("    </GrpHdr>\n");
            
            // Original Group Information and Status
            xml.append("    <OrgnlGrpInfAndSts>\n");
            xml.append("      <OrgnlMsgId>").append(escapeXml(originalMsgId)).append("</OrgnlMsgId>\n");
            xml.append("      <OrgnlMsgNmId>").append(escapeXml(originalMsgNmId)).append("</OrgnlMsgNmId>\n");
            xml.append("      <OrgnlNbOfTxs>1</OrgnlNbOfTxs>\n");
            xml.append("      <OrgnlCtrlSum>").append(escapeXml(originalCtrlSum)).append("</OrgnlCtrlSum>\n");
            xml.append("      <GrpSts>").append(escapeXml(status.getValue())).append("</GrpSts>\n");
            xml.append("    </OrgnlGrpInfAndSts>\n");
            
            // Original Payment Information and Status
            xml.append("    <OrgnlPmtInfAndSts>\n");
            xml.append("      <TxInfAndSts>\n");
            xml.append("        <OrgnlInstrId>").append(escapeXml(originalInstrId)).append("</OrgnlInstrId>\n");
            xml.append("        <OrgnlEndToEndId>").append(escapeXml(originalEndToEndId)).append("</OrgnlEndToEndId>\n");
            xml.append("        <TxSts>").append(escapeXml(status.getValue())).append("</TxSts>\n");
            
            // Status Reason Information
            xml.append("        <StsRsnInf>\n");
            xml.append("          <Rsn>\n");
            xml.append("            <Cd>").append(escapeXml(reasonCode)).append("</Cd>\n");
            xml.append("          </Rsn>\n");
            xml.append("          <AddtlInf>").append(escapeXml(additionalInfo)).append("</AddtlInf>\n");
            xml.append("        </StsRsnInf>\n");
            
            xml.append("      </TxInfAndSts>\n");
            xml.append("    </OrgnlPmtInfAndSts>\n");
            
            xml.append("  </FIToFIPmtStsRpt>\n");
            xml.append("</Document>");
            
            return xml.toString();
            
        } catch (Exception e) {
            log.error("Failed to generate PACS.002 message for E2E={}", 
                event.getEndToEndId(), e);
            throw new RuntimeException("Failed to generate PACS.002 message", e);
        }
    }
    
    /**
     * Generate unique message ID for PACS.002.
     */
    private String generateMessageId(PaymentEvent event) {
        String prefix = "WF-STATUS-";
        String timestamp = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14);
        String endToEndId = event.getEndToEndId() != null ? 
            event.getEndToEndId().substring(Math.max(0, event.getEndToEndId().length() - 6)) : "UNKNOWN";
        return prefix + timestamp + "-" + endToEndId;
    }
    
    /**
     * Get original message name ID from PaymentEvent.
     */
    private String getOriginalMessageNameId(PaymentEvent event) {
        if (event.getSourceMessageType() == null) {
            return "pacs.008.001.08"; // Default
        }
        
        String sourceType = event.getSourceMessageType().getValue();
        if ("ISO20022_PACS008".equals(sourceType)) {
            return "pacs.008.001.08";
        } else if ("ISO20022_PACS009".equals(sourceType)) {
            return "pacs.009.001.08";
        } else {
            return "pacs.008.001.08"; // Default
        }
    }
    
    /**
     * Get default additional information based on status.
     */
    private String getDefaultAdditionalInfo(Pacs002Status status) {
        switch (status) {
            case RCVD:
                return "Payment received and acknowledged";
            case ACCP:
                return "Payment accepted for processing";
            case ACCC:
                return "Payment accepted after customer profile validation";
            case PDNG:
                return "Payment pending further checks";
            case ACSP:
                return "Payment accepted, settlement in process";
            case ACSC:
                return "Payment accepted, settlement completed";
            case RJCT:
                return "Payment rejected";
            case CANC:
                return "Payment cancelled as requested";
            default:
                return "Payment status update";
        }
    }
    
    /**
     * Escape XML special characters.
     */
    private String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
}

