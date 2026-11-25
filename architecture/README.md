# FraudShield architecture

Each diagram answers one question. The Mermaid source is kept alongside the repository so changes can be reviewed with the code.

| View | Question | Evidence |
|---|---|---|
| [System context](diagrams/system-context.mmd) | Who uses FraudShield, and what does it connect to? | [Dashboard](../frontend/src/App.tsx#L313), [MCP tools](../mcp_server.py#L19), [Gemini endpoint](../backend/src/main/java/com/fraudshield/backend/service/GeminiService.java#L238) |
| [Runtime components](diagrams/runtime-components.mmd) | Which systems run separately, and how do they talk? | [Frontend URLs](../frontend/src/App.tsx#L65), [WebSocket registration](../backend/src/main/java/com/fraudshield/backend/config/WebSocketConfig.java#L19), [MCP data access](../mcp_server.py#L19), [MongoDB configuration](../backend/src/main/java/com/fraudshield/backend/config/MongoConfig.java#L14), [Vertex AI request](../backend/src/main/java/com/fraudshield/backend/service/GeminiService.java#L238) |
| [Fraud-detection sequence](diagrams/fraud-detection-flow.mmd) | What happens when an analyst starts the feed and a new transaction arrives? | [Start endpoint](../backend/src/main/java/com/fraudshield/backend/controller/IngestionController.java#L16), [Ingestion worker](../backend/src/main/java/com/fraudshield/backend/service/IngestionService.java#L289), [Case processing](../backend/src/main/java/com/fraudshield/backend/service/IngestionService.java#L363), [Gemini fallback](../backend/src/main/java/com/fraudshield/backend/service/GeminiService.java#L129) |
| [Deployment](diagrams/deployment.mmd) | What runs locally and what does the cloud deployment script target? | [Compose services](../docker-compose.yml#L3), [Frontend container](../frontend/Dockerfile#L10), [Cloud Run deployment](../deploy.sh#L41), [Frontend build substitutions](../frontend/cloudbuild.yaml#L1) |

## Runtime responsibilities

- **React dashboard.** The app runs in the analyst's browser. It initiates HTTP requests to the backend and opens the WebSocket. The backend pushes transaction and case events over that connection.
- **Spring Boot backend.** Controllers, repositories, the ingestion executor, the Gemini executor, and the Java investigation agent are part of one backend application. The sequence diagram uses separate participants for the two executors to show where execution can overlap.
- **MongoDB.** The backend persists users, transactions, and fraud cases. The Python MCP server can also query transactions directly.
- **Vertex AI / Gemini 2.5 Flash.** The backend submits an HTTPS request using transaction context, a user baseline, and recent history. Simulation can supply a result when the project is unconfigured or a Vertex request fails.
- **Python MCP server.** An MCP client can launch it over stdio. Its backend API calls cover open cases, user baseline lookup, case status, and investigation reports. Its direct PyMongo calls cover transaction history, receiver profiles, and fraud-network aggregations. See the [MCP data-access decision record](decisions/0002-mcp-data-access.md).

## Failure and configuration boundaries

- **MongoDB failure.** The ingestion controller acknowledges the start before the worker processes the feed. A database exception in the foreground loop reaches its outer error handler, which logs the failure and attempts a stop event. The async analysis worker logs its own failures, so a pending case may remain pending. [Ingestion start and error handling](../backend/src/main/java/com/fraudshield/backend/service/IngestionService.java#L289) · [Async worker](../backend/src/main/java/com/fraudshield/backend/service/IngestionService.java#L415)
- **Gemini failure.** The service can fall back to simulation. The original pending alert is sent before the AI work begins. [Gemini fallback](../backend/src/main/java/com/fraudshield/backend/service/GeminiService.java#L124) · [Pending case and initial alert](../backend/src/main/java/com/fraudshield/backend/service/IngestionService.java#L392)
- **WebSocket failure.** The server logs failed sends. The browser schedules a reconnect after three seconds for close codes other than normal closure. [Broadcast handler](../backend/src/main/java/com/fraudshield/backend/config/LiveStreamWebSocketHandler.java#L33) · [Dashboard reconnect](../frontend/src/App.tsx#L330)
- **Deployment configuration.** The backend reads the MongoDB connection from `SPRING_DATA_MONGODB_URI` and cloud settings from `GCP_PROJECT_ID` and `GCP_REGION`. The frontend takes `VITE_BACKEND_URL` at build time. Keep database credentials in the environment or the deployment's secret configuration. Both Cloud Run deploy commands request unauthenticated invocation at the Cloud Run boundary. The deployment view describes the checked-in script and Compose file. Deployed resources and access controls have not been verified. [Compose configuration](../docker-compose.yml#L3) · [Deploy commands](../deploy.sh#L50)

## Decisions and maintenance

- [0001 · Gemini enrichment after the initial alert](decisions/0001-gemini-analysis.md)
- [0002 · MCP data access through REST and MongoDB](decisions/0002-mcp-data-access.md)

Use source and configuration to verify every arrow's direction, transport, and purpose. Update the narrowest affected diagram when a connection, workflow, or deployment changes. Record unverified behavior explicitly. Treat the inferred Bricks inventory as a discovery aid and review its claims against the linked evidence before using them in these diagrams.
