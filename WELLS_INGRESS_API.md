# Wells Ingress REST API Documentation

## Overview

The Wells Ingress REST API allows internal Wells Fargo applications to submit payments that originate within the bank. This API is designed for:

- **Branch Applications**: Branch staff initiating payments on behalf of customers
- **Online Banking Portal**: Customers initiating payments through web/mobile applications
- **Scheduled Payment Systems**: Standing orders and recurring payments initiated by other systems

## Base URL

```
http://localhost:8084/api/v1/wells/payments
```

## Endpoints

### 1. Submit Payment (PACS.008 XML)

**Endpoint**: `POST /api/v1/wells/payments/submit`  
**Content-Type**: `application/xml`  
**Accept**: `application/json`

**Headers**:
- `X-Channel` (optional): Channel identifier (e.g., "BRANCH", "ONLINE", "SCHEDULED"). Default: "API"

**Request Body**: PACS.008 XML message

**Example Request**:
```http
POST /api/v1/wells/payments/submit HTTP/1.1
Host: localhost:8084
Content-Type: application/xml
X-Channel: BRANCH

<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.12">
  <FIToFICstmrCdtTrf>
    <GrpHdr>
      <MsgId>MSG-20241226-001</MsgId>
      <CreDtTm>2024-12-26T10:30:00Z</CreDtTm>
      <NbOfTxs>1</NbOfTxs>
    </GrpHdr>
    <CdtTrfTxInf>
      <PmtId>
        <EndToEndId>E2E-BRANCH-001</EndToEndId>
      </PmtId>
      <IntrBkSttlmAmt Ccy="USD">5000.00</IntrBkSttlmAmt>
      <DbtrAgt>
        <FinInstnId>
          <BICFI>WFBIUS6SXXX</BICFI>
        </FinInstnId>
      </DbtrAgt>
      <Dbtr>
        <Nm>John Doe</Nm>
      </Dbtr>
      <DbtrAcct>
        <Id>
          <Othr>
            <Id>US-CUST-12345</Id>
          </Othr>
        </Id>
      </DbtrAcct>
      <CdtrAgt>
        <FinInstnId>
          <BICFI>CHASUS33XXX</BICFI>
        </FinInstnId>
      </CdtrAgt>
      <Cdtr>
        <Nm>Jane Smith</Nm>
      </Cdtr>
      <CdtrAcct>
        <Id>
          <Othr>
            <Id>US-CUST-67890</Id>
          </Othr>
        </Id>
      </CdtrAcct>
      <RmtInf>
        <Ustrd>Invoice payment</Ustrd>
      </RmtInf>
    </CdtTrfTxInf>
  </FIToFICstmrCdtTrf>
</Document>
```

**Success Response** (202 Accepted):
```json
{
  "status": "ACCEPTED",
  "message": "Payment submitted successfully",
  "endToEndId": "E2E-BRANCH-001",
  "channel": "BRANCH",
  "topic": "ingress.wells.in",
  "partition": 5,
  "offset": 12345,
  "timestamp": 1703587800000
}
```

**Error Response** (400 Bad Request):
```json
{
  "status": "ERROR",
  "errorCode": "INVALID_REQUEST",
  "errorMessage": "PACS.008 message must contain EndToEndId",
  "timestamp": 1703587800000
}
```

---

### 2. Submit Payment (JSON - Simplified Format)

**Endpoint**: `POST /api/v1/wells/payments/submit`  
**Content-Type**: `application/json`  
**Accept**: `application/json`

**Headers**:
- `X-Channel` (optional): Channel identifier (e.g., "BRANCH", "ONLINE", "SCHEDULED"). Default: "API"

**Request Body**: JSON payment request

**Example Request**:
```http
POST /api/v1/wells/payments/submit HTTP/1.1
Host: localhost:8084
Content-Type: application/json
X-Channel: ONLINE

{
  "endToEndId": "E2E-ONLINE-20241226-001",
  "amount": 2500.00,
  "currency": "USD",
  "debtorAccount": "US-CUST-12345",
  "debtorName": "John Doe",
  "creditorAccount": "US-CUST-67890",
  "creditorName": "Jane Smith",
  "creditorBic": "CHASUS33XXX",
  "remittanceInfo": "Monthly payment"
}
```

**Success Response** (202 Accepted):
```json
{
  "status": "ACCEPTED",
  "message": "Payment submitted successfully",
  "endToEndId": "E2E-ONLINE-20241226-001",
  "channel": "ONLINE",
  "topic": "ingress.wells.in",
  "partition": 3,
  "offset": 67890,
  "timestamp": 1703587800000
}
```

**Error Response** (400 Bad Request):
```json
{
  "status": "ERROR",
  "errorCode": "VALIDATION_ERROR",
  "errorMessage": "Amount must be greater than zero",
  "timestamp": 1703587800000
}
```

---

### 3. Health Check

**Endpoint**: `GET /api/v1/wells/payments/health`

**Response** (200 OK):
```json
{
  "status": "UP",
  "service": "Wells Ingress API"
}
```

---

## Payment Request JSON Schema

### Required Fields

| Field | Type | Description |
|-------|------|-------------|
| `endToEndId` | String | Unique end-to-end identifier for the payment |
| `amount` | Decimal | Payment amount (must be > 0) |
| `currency` | String | Currency code (e.g., "USD", "EUR") |
| `debtorAccount` | String | Debtor account identifier |
| `creditorAccount` | String | Creditor account identifier |

### Optional Fields

| Field | Type | Description |
|-------|------|-------------|
| `msgId` | String | Message ID (auto-generated if not provided) |
| `debtorName` | String | Debtor name |
| `creditorName` | String | Creditor name |
| `creditorBic` | String | Creditor bank BIC (will be determined by routing if not provided) |
| `remittanceInfo` | String | Remittance information/reference |

---

## Channel Headers

The `X-Channel` header identifies the source of the payment:

- **BRANCH**: Payment initiated at a branch
- **ONLINE**: Payment initiated through online banking portal
- **SCHEDULED**: Payment from scheduled/standing order system
- **MOBILE**: Payment from mobile application
- **API**: Default for direct API calls

---

## Error Codes

| Error Code | HTTP Status | Description |
|------------|-------------|-------------|
| `INVALID_REQUEST` | 400 | Request is invalid (missing required fields, empty XML, etc.) |
| `VALIDATION_ERROR` | 400 | Payment request validation failed |
| `INVALID_FORMAT` | 400 | Message format is invalid (not PACS.008) |
| `SUBMISSION_ERROR` | 500 | Internal error during payment submission |

---

## Integration Examples

### Example 1: Branch Application (XML)

```java
// Branch application code
String pacs008Xml = loadPacs008FromFile("payment.xml");

HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://payment-ingress:8084/api/v1/wells/payments/submit"))
    .header("Content-Type", "application/xml")
    .header("X-Channel", "BRANCH")
    .POST(HttpRequest.BodyPublishers.ofString(pacs008Xml))
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
// Handle response
```

### Example 2: Online Portal (JSON)

```javascript
// Online portal JavaScript
const paymentRequest = {
    endToEndId: "E2E-ONLINE-" + Date.now(),
    amount: 1000.00,
    currency: "USD",
    debtorAccount: "US-CUST-12345",
    debtorName: "John Doe",
    creditorAccount: "US-CUST-67890",
    creditorName: "Jane Smith",
    creditorBic: "CHASUS33XXX",
    remittanceInfo: "Invoice #12345"
};

fetch('http://payment-ingress:8084/api/v1/wells/payments/submit', {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'X-Channel': 'ONLINE'
    },
    body: JSON.stringify(paymentRequest)
})
.then(response => response.json())
.then(data => {
    if (data.status === 'ACCEPTED') {
        console.log('Payment submitted:', data.endToEndId);
    } else {
        console.error('Error:', data.errorMessage);
    }
});
```

### Example 3: Scheduled Payment System (JSON)

```python
# Scheduled payment system (Python)
import requests

payment_request = {
    "endToEndId": f"E2E-SCHEDULED-{datetime.now().strftime('%Y%m%d%H%M%S')}",
    "amount": 500.00,
    "currency": "USD",
    "debtorAccount": "US-CUST-12345",
    "debtorName": "John Doe",
    "creditorAccount": "US-CUST-67890",
    "creditorName": "Utility Company",
    "remittanceInfo": "Monthly utility payment"
}

response = requests.post(
    "http://payment-ingress:8084/api/v1/wells/payments/submit",
    json=payment_request,
    headers={"X-Channel": "SCHEDULED"}
)

if response.status_code == 202:
    result = response.json()
    print(f"Payment submitted: {result['endToEndId']}")
else:
    print(f"Error: {response.json()['errorMessage']}")
```

---

## Payment Flow

1. **Internal Application** → Calls REST API endpoint
2. **Wells Ingress API** → Validates request and publishes to Kafka topic `ingress.wells.in`
3. **Wells Ingress Service** → Consumes from Kafka, parses message, converts to PaymentEvent
4. **Orchestrator** → Routes payment through processing pipeline
5. **Routing Logic** → Determines if payment goes out via FED/SWIFT or stays internal (IBT)

---

## Important Notes

1. **Idempotency**: Payments are identified by `endToEndId`. Duplicate `endToEndId` will be handled by the orchestrator.

2. **Asynchronous Processing**: The API returns immediately after publishing to Kafka. Payment processing happens asynchronously.

3. **Channel Tracking**: Use `X-Channel` header to track payment source for analytics and monitoring.

4. **Routing**: The routing logic will determine the egress path (FED/SWIFT/IBT) based on creditor party, not the ingress channel.

5. **Status Updates**: Payment status updates are sent via PACS.002 messages. Applications should subscribe to status notifications or poll for status.

---

## Security Considerations

- **Authentication**: Add authentication/authorization in production (OAuth2, JWT, etc.)
- **Rate Limiting**: Implement rate limiting per channel/application
- **Input Validation**: All inputs are validated before processing
- **HTTPS**: Use HTTPS in production environments

---

## Testing

### Using cURL (XML)
```bash
curl -X POST http://localhost:8084/api/v1/wells/payments/submit \
  -H "Content-Type: application/xml" \
  -H "X-Channel: BRANCH" \
  -d @payment.xml
```

### Using cURL (JSON)
```bash
curl -X POST http://localhost:8084/api/v1/wells/payments/submit \
  -H "Content-Type: application/json" \
  -H "X-Channel: ONLINE" \
  -d '{
    "endToEndId": "E2E-TEST-001",
    "amount": 100.00,
    "currency": "USD",
    "debtorAccount": "US-CUST-12345",
    "creditorAccount": "US-CUST-67890"
  }'
```

---

## Support

For issues or questions, contact the Payment Engine team.

