# Generate Sample Payment Flow
# This script creates sample files without running Kafka

Write-Host "Generating sample payment flow files..." -ForegroundColor Cyan

$sampleDir = "sample"
if (-not (Test-Path $sampleDir)) {
    New-Item -ItemType Directory -Path $sampleDir | Out-Null
}

# Create input PACS.008
$pacs008 = @"
<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
  <FIToFICstmrCdtTrf>
    <GrpHdr>
      <MsgId>HDFC-20251225-001</MsgId>
      <CreDtTm>2025-12-25T10:00:00</CreDtTm>
      <NbOfTxs>1</NbOfTxs>
      <SttlmInf>
        <SttlmMtd>CLRG</SttlmMtd>
      </SttlmInf>
    </GrpHdr>
    <CdtTrfTxInf>
      <PmtId>
        <InstrId>CUSTIND-A-20251225</InstrId>
        <EndToEndId>INV-HDFC-WF-20251225-001</EndToEndId>
      </PmtId>
      <Amt>
        <InstdAmt Ccy="USD">50000.00</InstdAmt>
      </Amt>
      <Dbtr>
        <Nm>Customer A (India)</Nm>
      </Dbtr>
      <DbtrAcct>
        <Id>
          <Othr>
            <Id>IND-CUSTA-ACC12345</Id>
          </Othr>
        </Id>
      </DbtrAcct>
      <DbtrAgt>
        <FinInstnId>
          <BIC>HDFCINBBXXX</BIC>
        </FinInstnId>
      </DbtrAgt>
      <CdtrAgt>
        <FinInstnId>
          <BIC>WFBIUS6SXXX</BIC>
        </FinInstnId>
      </CdtrAgt>
      <Cdtr>
        <Nm>Wells Fargo Customer B</Nm>
      </Cdtr>
      <CdtrAcct>
        <Id>
          <Othr>
            <Id>US-CUSTB-ACC67890</Id>
          </Othr>
        </Id>
      </CdtrAcct>
      <RmtInf>
        <Ustrd>Payment for Invoice INV-2025-001</Ustrd>
      </RmtInf>
    </CdtTrfTxInf>
  </FIToFICstmrCdtTrf>
</Document>
"@

$pacs008 | Out-File -FilePath "$sampleDir\01_input_pacs008.xml" -Encoding UTF8

# Generate PACS.002 status messages
$statuses = @(
    @{Code="RCVD"; File="02_status_RCVD.pacs.002.xml"; Info="Payment received and acknowledged"},
    @{Code="ACCP"; File="04_status_ACCP.pacs.002.xml"; Info="Payment accepted for processing after account validation"},
    @{Code="ACCC"; File="06_status_ACCC.pacs.002.xml"; Info="Payment accepted after customer profile validation (KYC/AML checks passed)"},
    @{Code="PDNG"; File="08_status_PDNG.pacs.002.xml"; Info="Payment pending further checks (sanctions passed)"},
    @{Code="ACSP"; File="10_status_ACSP.pacs.002.xml"; Info="Payment accepted, settlement in process"},
    @{Code="ACSC"; File="12_status_ACSC.pacs.002.xml"; Info="Payment accepted, settlement completed (funds credited to beneficiary account)"}
)

foreach ($status in $statuses) {
    $pacs002 = @"
<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
  <FIToFIPmtStsRpt>
    <GrpHdr>
      <MsgId>WF-STATUS-20251225-$(($statuses.IndexOf($status) + 1).ToString("00"))</MsgId>
      <CreDtTm>2025-12-25T10:0$($statuses.IndexOf($status) + 1):00</CreDtTm>
    </GrpHdr>
    <OrgnlGrpInfAndSts>
      <OrgnlMsgId>HDFC-20251225-001</OrgnlMsgId>
      <OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId>
      <GrpSts>$($status.Code)</GrpSts>
    </OrgnlGrpInfAndSts>
    <OrgnlPmtInfAndSts>
      <TxInfAndSts>
        <OrgnlInstrId>CUSTIND-A-20251225</OrgnlInstrId>
        <OrgnlEndToEndId>INV-HDFC-WF-20251225-001</OrgnlEndToEndId>
        <TxSts>$($status.Code)</TxSts>
        <StsRsnInf>
          <Rsn>
            <Cd>$($status.Code)</Cd>
          </Rsn>
          <AddtlInf>$($status.Info)</AddtlInf>
        </StsRsnInf>
      </TxInfAndSts>
    </OrgnlPmtInfAndSts>
  </FIToFIPmtStsRpt>
</Document>
"@
    $pacs002 | Out-File -FilePath "$sampleDir\$($status.File)" -Encoding UTF8
    Write-Host "  Generated: $($status.File)" -ForegroundColor Green
}

# Create summary
$summary = @"
# Payment Flow Simulation Summary

## Payment Details
- **End-to-End ID**: INV-HDFC-WF-20251225-001
- **Message ID**: HDFC-20251225-001
- **Amount**: USD 50,000.00
- **Debtor**: Customer A (India) - HDFC Bank
- **Creditor**: Wells Fargo Customer B - Wells Fargo Bank

## Processing Steps

1. **RCVD** - Payment received and acknowledged
2. **ACCP** - Accepted for processing (account validation passed)
3. **ACCC** - Accepted after customer profile validation (KYC/AML passed)
4. **PDNG** - Pending further checks (sanctions check passed)
5. **ACSP** - Accepted, settlement in process (balance check passed)
6. **ACSC** - Accepted, settlement completed (funds credited)

## Generated Files

### Input
- `01_input_pacs008.xml` - Initial PACS.008 message from HDFC Bank

### PACS.002 Status Messages
- `02_status_RCVD.pacs.002.xml` - Payment received
- `04_status_ACCP.pacs.002.xml` - Accepted for processing
- `06_status_ACCC.pacs.002.xml` - Accepted after customer validation
- `08_status_PDNG.pacs.002.xml` - Pending further checks
- `10_status_ACSP.pacs.002.xml` - Accepted, settlement in process
- `12_status_ACSC.pacs.002.xml` - Accepted, settlement completed

## Notes
- All processing steps completed successfully
- Payment was routed via SWIFT (direct)
- Sanctions checks passed (OFAC, UN, EU lists checked)
- Funds successfully credited to beneficiary account
"@

$summary | Out-File -FilePath "$sampleDir\SUMMARY.md" -Encoding UTF8

Write-Host "`n✓ Sample payment flow generated successfully!" -ForegroundColor Green
Write-Host "✓ Files saved to: $sampleDir/" -ForegroundColor Green
Write-Host "`nGenerated files:" -ForegroundColor Cyan
Get-ChildItem $sampleDir | ForEach-Object { Write-Host "  - $($_.Name)" -ForegroundColor White }

