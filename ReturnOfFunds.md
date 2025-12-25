ISO 20022 defines a set of standard external reason codes for returns and rejects (used in pacs.004 and pacs.002). For example, AC01 = Account Identifier Incorrect. These codes are part of the official ISO 20022 External Code Sets and cover account issues, regulatory blocks, technical errors, and more.

🔑 Common Standard Reason Codes in pacs.004 (Payment Return)
Code	Meaning	Typical Scenario
AC01	Account identifier incorrect	Invalid or closed beneficiary account.
AC04	Account closed	Beneficiary account no longer active.
AC06	Account blocked	Account frozen due to legal/regulatory reasons.
AM04	Insufficient funds	Debtor account lacks sufficient balance.
AM05	Duplicate payment	Payment already processed.
BE04	Incorrect creditor name	Beneficiary name mismatch.
BE05	Creditor missing	Beneficiary details not provided.
NARR	Narrative reason	Free-text explanation when no standard code fits.
RC01	Invalid bank identifier	Wrong BIC/clearing code for creditor agent.
RR01	Regulatory reason	Blocked due to sanctions/AML rules.
RR02	Regulatory reporting incomplete	Missing mandatory compliance data.
AG01	Transaction forbidden	Payment type not allowed (e.g., blocked service).
AG02	Invalid bank operation	Instruction not permitted by receiving bank.
Sources: ISO 20022 external code sets, industry guidesISO 20022 payments+1.

📌 How They Are Used
pacs.004 (Return): Wells → HDFC when payment cannot be applied. The <RtrRsnInf> element carries the reason code (e.g., AC01).

pacs.002 (Reject): Similar codes used when rejecting at validation stage.

camt.056 (Cancellation): May reference reason codes when requesting cancellation.