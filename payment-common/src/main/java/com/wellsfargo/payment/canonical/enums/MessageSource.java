package com.wellsfargo.payment.canonical.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Source message format/rail.
 */
public enum MessageSource {
    SWIFT_MT("SWIFT_MT"),                    // SWIFT MT message (MT103, MT202, etc.)
    ISO20022_PACS008("ISO20022_PACS008"),    // ISO 20022 Customer Credit Transfer
    ISO20022_PACS009("ISO20022_PACS009"),    // ISO 20022 Financial Institution Transfer
    FED("FED"),                              // FED message format
    CHIPS("CHIPS"),                          // CHIPS message format
    INTERNAL("INTERNAL");                    // Internal format

    private final String value;

    MessageSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}

