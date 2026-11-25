# FraudShield

FraudShield is a fraud-investigation demo. Its React dashboard starts a transaction feed, displays live alerts, and lets analysts review cases. A Spring Boot backend checks transactions against user baselines, stores results in MongoDB, and calls Gemini through Vertex AI when configured. A separate Python MCP server exposes investigation tools to MCP clients.

Start with the [architecture guide](architecture/README.md) for the context, runtime, fraud-detection, and deployment views. Source code and deployment configuration establish implemented behavior. The diagrams document that evidence, and decision records capture rationale and open questions.

See [project context](CONTEXT.md) for background and [agent guidance](skills/agentic_architecture.md) for the repository's AI and MCP conventions.
