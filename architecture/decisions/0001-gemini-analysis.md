# 0001 · Enrich fraud alerts with Gemini in the background

**Status** · Recorded from the current implementation.

The ingestion worker saves an open case with pending analysis and broadcasts an initial alert before it submits work to the Gemini executor. The async worker reads recent history, analyzes the transaction, and updates the case if it still exists. The Gemini service falls back to simulated analysis when cloud configuration, authentication, or the request fails.

This lets the dashboard show an initial alert while enrichment is still running. The foreground transaction save and the worker may overlap. A failure during async processing can leave the case pending. Simulation also means a resulting analysis may come from local fallback logic, so the existence of an analysis alone cannot establish that Vertex AI ran.

[Case creation and executor submission](../../backend/src/main/java/com/fraudshield/backend/service/IngestionService.java#L392) · [Gemini analysis and fallback](../../backend/src/main/java/com/fraudshield/backend/service/GeminiService.java#L124)
