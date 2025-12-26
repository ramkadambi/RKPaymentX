package com.wellsfargo.payment.notification;

import com.wellsfargo.payment.canonical.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * Generator for camt.029 Resolution of Investigation messages.
 * 
 * camt.029 is used to respond to camt.056 cancellation requests when:
 * - Refund is refused (e.g., goods already shipped, payment already applied)
 * - Refund is pending (PDCR - Pending Cancellation Request)
 * 
 * This is part of the "Investigation Layer" - it provides the decision,
 * but does NOT move funds. The actual fund movement happens via pacs.004.
 */
public class Camt029Generator {
    
    private static final Logger log = LoggerFactory.getLogger(Camt029Generator.class);
    private static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:camt.029.001.09";
    
    /**
     * Resolution status codes for camt.029.
     */
    public enum ResolutionStatus {
        /** Refused - refund cannot be processed */
        RJCT("RJCT"),
        /** Pending Cancellation Request - still investigating */
        PDCR("PDCR"),
        /** Accepted - refund will be processed (leads to pacs.004) */
        ACCP("ACCP");
        
        private final String value;
        
        ResolutionStatus(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    /**
     * Generate camt.029 Resolution of Investigation XML.
     * 
     * @param event Original PaymentEvent
     * @param originalCamt056MsgId Original camt.056 message ID that triggered this investigation
     * @param caseId Case ID for tracking the investigation
     * @param status Resolution status (RJCT, PDCR, or ACCP)
     * @param reasonCode Reason code (e.g., "DUPL", "FRAD", "CUST")
     * @param additionalInfo Additional information about the resolution
     * @return camt.029 XML message
     */
    public String generateCamt029(PaymentEvent event, String originalCamt056MsgId, 
                                   String caseId, ResolutionStatus status, 
                                   String reasonCode, String additionalInfo) {
        try {
            String msgId = generateMessageId();
            String creDtTm = Instant.now().toString();
            String originalMsgId = event.getMsgId() != null ? event.getMsgId() : "";
            String originalEndToEndId = event.getEndToEndId() != null ? event.getEndToEndId() : "";
            String originalInstrId = event.getEndToEndId() != null ? event.getEndToEndId() : "";
            
            // Default reason code if not provided
            if (reasonCode == null || reasonCode.isEmpty()) {
                reasonCode = status == ResolutionStatus.RJCT ? "DUPL" : "CUST";
            }
            
            // Default additional info if not provided
            if (additionalInfo == null || additionalInfo.isEmpty()) {
                additionalInfo = getDefaultAdditionalInfo(status, reasonCode);
            }
            
            // Generate case ID if not provided
            if (caseId == null || caseId.isEmpty()) {
                caseId = generateCaseId();
            }
            
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<Document xmlns=\"").append(NAMESPACE).append("\">\n");
            xml.append("  <RsltnOfInvstgtn>\n");
            
            // Group Header
            xml.append("    <GrpHdr>\n");
            xml.append("      <MsgId>").append(escapeXml(msgId)).append("</MsgId>\n");
            xml.append("      <CreDtTm>").append(escapeXml(creDtTm)).append("</CreDtTm>\n");
            xml.append("    </GrpHdr>\n");
            
            // Original Group Information (references original camt.056)
            xml.append("    <OrgnlGrpInf>\n");
            xml.append("      <OrgnlMsgId>").append(escapeXml(originalCamt056MsgId)).append("</OrgnlMsgId>\n");
            xml.append("      <OrgnlMsgNmId>camt.056.001.08</OrgnlMsgNmId>\n");
            xml.append("    </OrgnlGrpInf>\n");
            
            // Resolution Report
            xml.append("    <RsltnRpt>\n");
            xml.append("      <OrgnlPmtInfAndCxl>\n");
            xml.append("        <OrgnlPmtInfId>").append(escapeXml(originalInstrId)).append("</OrgnlPmtInfId>\n");
            xml.append("        <OrgnlEndToEndId>").append(escapeXml(originalEndToEndId)).append("</OrgnlEndToEndId>\n");
            xml.append("        <CxlStsId>").append(escapeXml(caseId)).append("</CxlStsId>\n");
            
            // Resolution
            xml.append("        <Rsltn>\n");
            xml.append("          <RslvdCase>\n");
            xml.append("            <Id>").append(escapeXml(caseId)).append("</Id>\n");
            xml.append("            <Sts>").append(escapeXml(status.getValue())).append("</Sts>\n");
            xml.append("            <RslvdRsn>\n");
            xml.append("              <Cd>").append(escapeXml(reasonCode)).append("</Cd>\n");
            xml.append("            </RslvdRsn>\n");
            xml.append("            <AddtlInf>").append(escapeXml(additionalInfo)).append("</AddtlInf>\n");
            xml.append("          </RslvdCase>\n");
            xml.append("        </Rsltn>\n");
            
            xml.append("      </OrgnlPmtInfAndCxl>\n");
            xml.append("    </RsltnRpt>\n");
            xml.append("  </RsltnOfInvstgtn>\n");
            xml.append("</Document>");
            
            return xml.toString();
            
        } catch (Exception e) {
            log.error("Failed to generate camt.029 message for E2E={}", 
                event.getEndToEndId(), e);
            throw new RuntimeException("Failed to generate camt.029 message", e);
        }
    }
    
    /**
     * Generate unique message ID for camt.029.
     */
    private String generateMessageId() {
        String prefix = "WF-RES-";
        String timestamp = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14);
        String uniqueId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return prefix + timestamp + "-" + uniqueId;
    }
    
    /**
     * Generate unique case ID for investigation tracking.
     */
    private String generateCaseId() {
        String prefix = "CASE-";
        String timestamp = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14);
        String uniqueId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return prefix + timestamp + "-" + uniqueId;
    }
    
    /**
     * Get default additional information based on status and reason code.
     */
    private String getDefaultAdditionalInfo(ResolutionStatus status, String reasonCode) {
        if (status == ResolutionStatus.RJCT) {
            switch (reasonCode) {
                case "DUPL":
                    return "Payment already applied - cannot be refunded";
                case "FRAD":
                    return "Payment verified as legitimate - refund refused";
                case "SHIP":
                    return "Goods already shipped - refund refused";
                default:
                    return "Refund refused: " + reasonCode;
            }
        } else if (status == ResolutionStatus.PDCR) {
            return "Pending customer approval for refund";
        } else {
            return "Refund approved - will be processed";
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

