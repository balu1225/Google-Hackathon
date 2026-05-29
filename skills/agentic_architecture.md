# Agentic Architecture & AI-Ready Guidelines

To ensure the FraudShield project is sustainable, scalable, and optimized for both internal AI features and external AI agents (such as MCP clients), we must adhere to the following architecture guidelines.

## 1. AI-Ready API Design
AI agents interact best with clean, predictable, and self-documenting APIs.
* **OpenAPI Documentation**: Maintain an OpenAPI/Swagger definition of the backend APIs.
* **Semantic Field Names**: Avoid ambiguous keys. Use descriptive names like `isHighRisk` rather than `flag` or `state`.
* **Structured Error Responses**: When an API fails, return a JSON error object specifying an `errorCode`, a developer-facing `message`, and `suggestedAction` instead of raw stack traces.

## 2. Model Context Protocol (MCP) Integration
To allow external AI assistants (like Claude, Gemini, etc.) to help human analysts investigate fraud, we should structure our backend to be easily wrapped by an MCP server.
* **Tool Specifications**: Expose endpoints that map directly to agent tools:
  * `getTransactionById(transactionId)` - Retrieve full details of a transaction.
  * `getUserProfile(accountId)` - Fetch user baseline behaviors and transaction histories.
  * `flagTransactionAsFraud(transactionId, reason)` - Manually trigger a fraud case.
  * `updateCaseStatus(caseId, status, comments)` - Move a case from OPEN to CLOSED or ACCOUNT_FROZEN.
* **Data Context**: Allow agents to fetch historical contexts (e.g., "get last 10 transactions for user X") to make judgements.

## 3. Structured Internal AI Analysis (Gemini Integration)
Our internal fraud detection engine uses Gemini to perform cognitive analysis on suspicious transactions.
* **Structured Outputs**: Always request response formatting using a JSON schema. Avoid raw text outputs from Gemini.
* **Schema Definition**:
  ```json
  {
    "riskScore": 0.95,
    "confidenceLevel": "HIGH",
    "primarySignals": ["Unusual location (Paris vs baseline New York)", "Transaction value is 4x average"],
    "detailedReasoning": "The transaction occurred in Paris, France using a web browser. The user's baseline profile indicates they reside in New York, have never transacted in France, and their average transaction value is $50, which is significantly lower than the current $200 request.",
    "recommendedAction": "FREEZE_ACCOUNT"
  }
  ```
* **Context Windowing**: When calling Gemini, include the transaction under analysis AND the user's historical baseline profile. Do not pass unnecessary database bloat.

## 4. Agent Audit Logs (Transparency)
* Every time the AI agent flags a case, it must record:
  * The exact prompt version or model used.
  * The system decision inputs (e.g., baseline parameters vs actual parameters).
  * The structured reasoning output.
* This ensures that human analysts can audit the agent's decisions and detect bias or false positive trends.
