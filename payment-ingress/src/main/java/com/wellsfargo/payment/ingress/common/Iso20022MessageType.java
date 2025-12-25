package com.wellsfargo.payment.ingress.common;

/**
 * ISO 20022 message type enumeration.
 * 
 * Represents the two main payment message types:
 * - PACS_008: Customer Credit Transfer (FIToFICstmrCdtTrf)
 * - PACS_009: Financial Institution Credit Transfer (FICdtTrf)
 */
public enum Iso20022MessageType {
    PACS_008,
    PACS_009
}

