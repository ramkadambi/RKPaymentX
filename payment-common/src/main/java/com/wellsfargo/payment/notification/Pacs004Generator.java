package com.wellsfargo.payment.notification;

import com.wellsfargo.payment.canonical.PaymentEvent;
import com.wellsfargo.payment.canonical.enums.ReturnReasonCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * Generator for PACS.004 Payment Return messages.
 * 
 * PACS.004 is used to return funds to the instructing bank when a payment
 * cannot be processed or needs to be returned due to business reasons.
 * This generator creates ISO 20022 compliant XML messages.
 */
public class Pacs004Generator {
    
    private static final Logger log = LoggerFactory.getLogger(Pacs004Generator.class);
    private static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09";
    
    /**
     * Generate PACS.004 payment return XML from PaymentEvent and return reason.
     * 
     * @param event Original PaymentEvent
     * @param returnReasonCode ISO 20022 return reason code (e.g., AC01, RR01, etc.)
     * @param additionalInfo Additional information about the return
     * @return PACS.004 XML message
     */
    public String generatePacs004(PaymentEvent event, ReturnReasonCode returnReasonCode, 
                                   String additionalInfo) {
        return generatePacs004(event, returnReasonCode.getCode(), additionalInfo);
    }
    
    /**
     * Generate PACS.004 payment return XML from PaymentEvent and return reason code string.
     * 
     * @param event Original PaymentEvent
     * @param returnReasonCode ISO 20022 return reason code string (e.g., "AC01", "RR01", etc.)
     * @param additionalInfo Additional information about the return
     * @return PACS.004 XML message
     */
    public String generatePacs004(PaymentEvent event, String returnReasonCode, 
                                   String additionalInfo) {
        try {
            String msgId = generateMessageId(event);
            String creDtTm = Instant.now().toString();
            String originalMsgId = event.getMsgId() != null ? event.getMsgId() : "";
            String originalMsgNmId = getOriginalMessageNameId(event);
            String originalInstrId = event.getEndToEndId() != null ? event.getEndToEndId() : "";
            String originalEndToEndId = event.getEndToEndId() != null ? event.getEndToEndId() : "";
            String returnId = generateReturnId(event);
            String returnAmount = event.getAmount() != null ? event.getAmount().toString() : "0.00";
            String currency = event.getCurrency() != null ? event.getCurrency() : "USD";
            
            // Default additional info if not provided
            if (additionalInfo == null || additionalInfo.isEmpty()) {
                additionalInfo = "Payment returned: " + returnReasonCode;
            }
            
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<Document xmlns=\"").append(NAMESPACE).append("\">\n");
            xml.append("  <PmtRtr>\n");
            
            // Group Header
            xml.append("    <GrpHdr>\n");
            xml.append("      <MsgId>").append(escapeXml(msgId)).append("</MsgId>\n");
            xml.append("      <CreDtTm>").append(escapeXml(creDtTm)).append("</CreDtTm>\n");
            xml.append("    </GrpHdr>\n");
            
            // Original Group Information
            xml.append("    <OrgnlGrpInf>\n");
            xml.append("      <OrgnlMsgId>").append(escapeXml(originalMsgId)).append("</OrgnlMsgId>\n");
            xml.append("      <OrgnlMsgNmId>").append(escapeXml(originalMsgNmId)).append("</OrgnlMsgNmId>\n");
            xml.append("    </OrgnlGrpInf>\n");
            
            // Transaction Information
            xml.append("    <TxInf>\n");
            xml.append("      <OrgnlInstrId>").append(escapeXml(originalInstrId)).append("</OrgnlInstrId>\n");
            xml.append("      <OrgnlEndToEndId>").append(escapeXml(originalEndToEndId)).append("</OrgnlEndToEndId>\n");
            xml.append("      <RtrId>").append(escapeXml(returnId)).append("</RtrId>\n");
            
            // Return Reason Information
            xml.append("      <RtrRsnInf>\n");
            xml.append("        <Rsn>\n");
            xml.append("          <Cd>").append(escapeXml(returnReasonCode)).append("</Cd>\n");
            xml.append("        </Rsn>\n");
            xml.append("        <AddtlInf>").append(escapeXml(additionalInfo)).append("</AddtlInf>\n");
            xml.append("      </RtrRsnInf>\n");
            
            // Return Amount
            xml.append("      <RtrAmt Ccy=\"").append(escapeXml(currency)).append("\">")
                .append(escapeXml(returnAmount)).append("</RtrAmt>\n");
            
            xml.append("    </TxInf>\n");
            xml.append("  </PmtRtr>\n");
            xml.append("</Document>");
            
            return xml.toString();
            
        } catch (Exception e) {
            log.error("Failed to generate PACS.004 message for E2E={}", 
                event.getEndToEndId(), e);
            throw new RuntimeException("Failed to generate PACS.004 message", e);
        }
    }
    
    /**
     * Generate unique message ID for PACS.004.
     */
    private String generateMessageId(PaymentEvent event) {
        String prefix = "WF-RETURN-";
        String timestamp = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14);
        String endToEndId = event.getEndToEndId() != null ? 
            event.getEndToEndId().substring(Math.max(0, event.getEndToEndId().length() - 6)) : "UNKNOWN";
        return prefix + timestamp + "-" + endToEndId;
    }
    
    /**
     * Generate unique return ID for PACS.004.
     */
    private String generateReturnId(PaymentEvent event) {
        String prefix = "WF-RTR-";
        String timestamp = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14);
        String uniqueId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return prefix + timestamp + "-" + uniqueId;
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

