# 0002 · MCP data access through REST and MongoDB

**Status** · Current behavior verified. Original rationale is open.

| MCP operation | Observed mechanism |
|---|---|
| List open cases and fetch a user's baseline | Backend REST via `httpx` |
| Update case status and submit an investigation report | Backend REST via `httpx` |
| Look up transaction history and receiver profiles | Direct MongoDB queries via PyMongo |
| Compute fraud-network statistics | Direct MongoDB aggregation via PyMongo |

The direct reads couple these tools to the transaction collection and its schema. The case updates in this server use the application's REST endpoints, so they pass through the backend's case logic. The repository does not establish why direct reads were chosen or which database permissions are granted to the MCP process. Record those decisions when they are confirmed.

[REST case and baseline tools](../../mcp_server.py#L19) · [Transaction query](../../mcp_server.py#L59) · [Case status tool](../../mcp_server.py#L76) · [Fraud-network aggregation](../../mcp_server.py#L123)
