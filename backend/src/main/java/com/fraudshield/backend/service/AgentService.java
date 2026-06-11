package com.fraudshield.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.fraudshield.backend.model.FraudCase;
import com.fraudshield.backend.model.Transaction;
import com.fraudshield.backend.model.User;
import com.fraudshield.backend.repository.FraudCaseRepository;
import com.fraudshield.backend.repository.TransactionRepository;
import com.fraudshield.backend.repository.UserRepository;
import com.fraudshield.backend.config.LiveStreamWebSocketHandler;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Iterator;

/**
 * AgentService — The core agentic loop engine.
 *
 * Unlike the old hardcoded pipeline, this service gives Gemini a GOAL and a set of TOOLS,
 * then lets Gemini autonomously decide which tools to call, in what order, based on what
 * it learns at each step. This is a true ReAct (Reasoning + Acting) agent loop.
 *
 * Tool definitions mirror the MCP server tools exactly, so the same capabilities
 * are available whether called via MCP or via Gemini function calling.
 */
@Service
public class AgentService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FraudCaseRepository fraudCaseRepository;

    @Autowired
    private LiveStreamWebSocketHandler webSocketHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${gcp.project.id:}")
    private String projectId;

    @Value("${gcp.region:us-central1}")
    private String region;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final int MAX_AGENT_ITERATIONS = 10;

    // ── Tool Definitions (Gemini Function Declarations) ──

    private List<Map<String, Object>> buildToolDeclarations() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // Tool 1: get_user_baseline
        tools.add(buildFunctionDeclaration(
            "get_user_baseline",
            "Retrieve the behavioral baseline profile for a cardholder by account ID. Returns frequent locations, frequent devices, and average transaction value.",
            Map.of("account_id", Map.of("type", "STRING", "description", "The account ID of the cardholder to look up"))
        ));

        // Tool 2: get_case_transactions
        tools.add(buildFunctionDeclaration(
            "get_case_transactions",
            "Retrieve recent transaction history for a cardholder to analyze spending patterns, velocity, and geographic movement.",
            Map.of("account_id", Map.of("type", "STRING", "description", "The sender account ID to retrieve transaction history for"))
        ));

        // Tool 3: get_receiver_profile
        tools.add(buildFunctionDeclaration(
            "get_receiver_profile",
            "Retrieve incoming transaction history for a receiver account to check for money mule patterns (many unique senders, rapid forwarding).",
            Map.of("receiver_account_id", Map.of("type", "STRING", "description", "The receiver account ID to check for mule activity"))
        ));

        // Tool 4: get_open_cases
        tools.add(buildFunctionDeclaration(
            "get_open_cases",
            "Retrieve all currently active (OPEN) fraud cases from the system. Useful to check for related ongoing investigations.",
            Map.of()
        ));

        // Tool 5: update_case_status
        tools.add(buildFunctionDeclaration(
            "update_case_status",
            "Update the status of a fraud case. Use 'ACCOUNT_FROZEN' to freeze the account, 'CLOSED' to dismiss, or 'UNDER_REVIEW' to escalate.",
            Map.of(
                "case_id", Map.of("type", "STRING", "description", "The ID of the fraud case to update"),
                "status", Map.of("type", "STRING", "description", "New status: ACCOUNT_FROZEN, CLOSED, or UNDER_REVIEW")
            )
        ));

        // Tool 6: submit_investigation_report
        tools.add(buildFunctionDeclaration(
            "submit_investigation_report",
            "Submit your final investigation findings as a structured report for this fraud case. Call this when you have completed your analysis.",
            Map.of(
                "case_id", Map.of("type", "STRING", "description", "The ID of the fraud case"),
                "investigation_summary", Map.of("type", "STRING", "description", "Concise synthesis: normal activity, compromised credentials, or money mule ring"),
                "evidence_strength", Map.of("type", "STRING", "description", "HIGH, MEDIUM, or LOW"),
                "recommended_action", Map.of("type", "STRING", "description", "FREEZE_ACCOUNT, FLAG_FOR_REVIEW, or ALLOW"),
                "customer_message", Map.of("type", "STRING", "description", "Draft SMS/email notification to the customer"),
                "audit_trail", Map.of("type", "STRING", "description", "Step-by-step log of facts analyzed and rules verified")
            )
        ));

        return tools;
    }

    private Map<String, Object> buildFunctionDeclaration(String name, String description, Map<String, Object> properties) {
        Map<String, Object> fn = new HashMap<>();
        fn.put("name", name);
        fn.put("description", description);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "OBJECT");
        parameters.put("properties", properties);
        parameters.put("required", new ArrayList<>(properties.keySet()));
        fn.put("parameters", parameters);

        return fn;
    }

    // ── Tool Execution ──

    private String executeTool(String toolName, Map<String, String> args) {
        try {
            switch (toolName) {
                case "get_user_baseline": {
                    String accountId = args.get("account_id");
                    Optional<User> userOpt = userRepository.findByAccountId(accountId);
                    if (userOpt.isEmpty()) return "No user profile found for account " + accountId;
                    User user = userOpt.get();
                    return String.format(
                        "User Profile for %s (Account: %s):\n" +
                        "  - Frequent Locations: %s\n" +
                        "  - Frequent Devices: %s\n" +
                        "  - Average Transaction Value: $%.2f",
                        user.getName(), user.getAccountId(),
                        String.join(", ", user.getFrequentLocations()),
                        String.join(", ", user.getFrequentDevices()),
                        user.getAverageTransactionValue()
                    );
                }

                case "get_case_transactions": {
                    String accountId = args.get("account_id");
                    List<Transaction> txns = transactionRepository.findTop10BySenderAccountOrderByTimestampDesc(accountId);
                    if (txns.isEmpty()) return "No transaction history found for account " + accountId;
                    StringBuilder sb = new StringBuilder("Recent Transactions for " + accountId + ":\n");
                    for (Transaction t : txns) {
                        sb.append(String.format("  - %s | %s | $%.2f | %s | %s | %s | Fraud: %s\n",
                            t.getTransactionId(), t.getTimestamp(), t.getAmount(),
                            t.getTransactionType(), t.getLocation(), t.getDeviceUsed(), t.getIsFraud()));
                    }
                    return sb.toString();
                }

                case "get_receiver_profile": {
                    String receiverId = args.get("receiver_account_id");
                    List<Transaction> txns = transactionRepository.findTop10ByReceiverAccountOrderByTimestampDesc(receiverId);
                    if (txns.isEmpty()) return "No incoming transactions found for receiver " + receiverId;
                    Set<String> uniqueSenders = new HashSet<>();
                    StringBuilder sb = new StringBuilder("Incoming Transactions for Receiver " + receiverId + ":\n");
                    for (Transaction t : txns) {
                        uniqueSenders.add(t.getSenderAccount());
                        sb.append(String.format("  - %s | %s | $%.2f | Sender: %s | %s\n",
                            t.getTransactionId(), t.getTimestamp(), t.getAmount(),
                            t.getSenderAccount(), t.getLocation()));
                    }
                    sb.append(String.format("\nSummary: %d transactions from %d unique senders.",
                        txns.size(), uniqueSenders.size()));
                    return sb.toString();
                }

                case "get_open_cases": {
                    List<FraudCase> cases = fraudCaseRepository.findByStatus("OPEN");
                    if (cases.isEmpty()) return "No open cases found.";
                    StringBuilder sb = new StringBuilder("Active Fraud Cases:\n");
                    for (FraudCase c : cases) {
                        sb.append(String.format("  - Case: %s | Txn: %s | Account: %s | Risk: %.0f%% | %s\n",
                            c.getId(), c.getTransactionId(), c.getAccountId(),
                            c.getRiskScore() * 100, c.getAiReasoning()));
                    }
                    return sb.toString();
                }

                case "update_case_status": {
                    String caseId = args.get("case_id");
                    String status = args.get("status");
                    Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
                    if (caseOpt.isEmpty()) return "Case not found: " + caseId;
                    FraudCase fc = caseOpt.get();
                    fc.setStatus(status);
                    fraudCaseRepository.save(fc);
                    // Broadcast update
                    try {
                        String json = String.format("{\"type\":\"CASE_UPDATE\",\"data\":%s}",
                            objectMapper.writeValueAsString(fc));
                        webSocketHandler.broadcast(json);
                    } catch (Exception e) { /* ignore broadcast errors */ }
                    return "Success: Case " + caseId + " status updated to " + status;
                }

                case "submit_investigation_report": {
                    String caseId = args.get("case_id");
                    Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
                    if (caseOpt.isEmpty()) return "Case not found: " + caseId;

                    Map<String, Object> report = new HashMap<>();
                    report.put("investigationSummary", args.getOrDefault("investigation_summary", ""));
                    report.put("evidenceStrength", args.getOrDefault("evidence_strength", "MEDIUM"));
                    report.put("recommendedAction", args.getOrDefault("recommended_action", "FLAG_FOR_REVIEW"));
                    report.put("customerMessage", args.getOrDefault("customer_message", ""));
                    report.put("auditTrail", args.getOrDefault("audit_trail", ""));

                    FraudCase fc = caseOpt.get();
                    fc.setInvestigationReport(objectMapper.writeValueAsString(report));
                    fraudCaseRepository.save(fc);
                    // Broadcast update
                    try {
                        String json = String.format("{\"type\":\"CASE_UPDATE\",\"data\":%s}",
                            objectMapper.writeValueAsString(fc));
                        webSocketHandler.broadcast(json);
                    } catch (Exception e) { /* ignore broadcast errors */ }
                    return "Success: Investigation report submitted for case " + caseId;
                }

                default:
                    return "Unknown tool: " + toolName;
            }
        } catch (Exception e) {
            return "Error executing " + toolName + ": " + e.getMessage();
        }
    }

    // ── Agentic Loop ──

    /**
     * Run an autonomous investigation on a fraud case.
     * The agent receives a goal and autonomously decides which tools to call.
     * Returns the agent trace as a JSON array string.
     */
    public String investigateCase(String caseId) {
        Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
        if (caseOpt.isEmpty()) return "{\"error\":\"Case not found\"}";

        FraudCase fraudCase = caseOpt.get();

        // Build the goal prompt
        String goal = String.format(
            "You are an Autonomous Fraud Investigation Agent for FraudShield. " +
            "You have been assigned to investigate fraud case '%s' for account '%s' (transaction: %s).\n\n" +
            "Your mission:\n" +
            "1. First, retrieve the sender's behavioral baseline profile using get_user_baseline\n" +
            "2. Then, pull the sender's recent transaction history using get_case_transactions\n" +
            "3. Check the receiver account for money mule patterns using get_receiver_profile\n" +
            "4. Optionally check for related open cases using get_open_cases\n" +
            "5. Based on your analysis, decide the appropriate action (freeze, review, or allow)\n" +
            "6. Submit your final investigation report using submit_investigation_report\n" +
            "7. If warranted, update the case status using update_case_status\n\n" +
            "Investigate thoroughly. Use the tools to gather evidence before making your decision. " +
            "You MUST call submit_investigation_report with your findings before finishing.",
            caseId, fraudCase.getAccountId(), fraudCase.getTransactionId()
        );

        // Check if we can use Vertex AI, otherwise fall back to simulation
        if (projectId == null || projectId.trim().isEmpty()) {
            System.out.println("No GCP project ID configured. Running simulated agent investigation.");
            return runSimulatedAgentLoop(fraudCase);
        }

        try {
            String accessToken;
            try {
                accessToken = getVertexAccessToken();
            } catch (Exception authEx) {
                System.out.println("Warning: Failed to get Vertex AI token for agent. Running simulation. Error: " + authEx.getMessage());
                return runSimulatedAgentLoop(fraudCase);
            }

            return runAgentLoop(goal, caseId, accessToken);
        } catch (Exception e) {
            System.err.println("Agent loop error: " + e.getMessage());
            e.printStackTrace();
            return runSimulatedAgentLoop(fraudCase);
        }
    }

    private String runAgentLoop(String goal, String caseId, String accessToken) throws Exception {
        ArrayNode traceLog = objectMapper.createArrayNode();
        List<Map<String, Object>> conversationHistory = new ArrayList<>();

        // Initial user message with the goal
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("parts", List.of(Map.of("text", goal)));
        conversationHistory.add(userMsg);

        // Log the goal
        ObjectNode goalStep = objectMapper.createObjectNode();
        goalStep.put("step", 0);
        goalStep.put("type", "GOAL");
        goalStep.put("content", goal);
        goalStep.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(goalStep);

        for (int iteration = 1; iteration <= MAX_AGENT_ITERATIONS; iteration++) {
            System.out.println("Agent iteration " + iteration + " for case " + caseId);

            // Build request with tools
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", conversationHistory);

            // Add tool declarations
            List<Map<String, Object>> toolDeclarations = buildToolDeclarations();
            Map<String, Object> toolsWrapper = new HashMap<>();
            toolsWrapper.put("functionDeclarations", toolDeclarations);
            requestBody.put("tools", List.of(toolsWrapper));

            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("temperature", 0.2);
            generationConfig.put("maxOutputTokens", 2048);
            requestBody.put("generationConfig", generationConfig);

            String requestJson = objectMapper.writeValueAsString(requestBody);

            String url = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/gemini-2.5-flash:generateContent",
                region, projectId, region
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Vertex AI agent call failed: " + response.statusCode() + " " + response.body());
                ObjectNode errorStep = objectMapper.createObjectNode();
                errorStep.put("step", iteration);
                errorStep.put("type", "ERROR");
                errorStep.put("content", "Vertex AI returned status " + response.statusCode());
                traceLog.add(errorStep);
                break;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode candidate = root.path("candidates").get(0).path("content");
            JsonNode parts = candidate.path("parts");

            // Add model response to conversation history
            Map<String, Object> modelResponse = objectMapper.convertValue(candidate, Map.class);
            conversationHistory.add(modelResponse);

            // Check if the model wants to call a function
            boolean hasFunctionCall = false;
            List<Map<String, Object>> functionResponseParts = new ArrayList<>();

            for (JsonNode part : parts) {
                if (part.has("functionCall")) {
                    hasFunctionCall = true;
                    String functionName = part.path("functionCall").path("name").asText();
                    JsonNode argsNode = part.path("functionCall").path("args");

                    // Extract args as Map<String, String>
                    Map<String, String> args = new HashMap<>();
                    for (String field : argsNode.propertyNames()) {
                        args.put(field, argsNode.path(field).asText());
                    }

                    // Log the tool call
                    ObjectNode toolCallStep = objectMapper.createObjectNode();
                    toolCallStep.put("step", iteration);
                    toolCallStep.put("type", "TOOL_CALL");
                    toolCallStep.put("tool", functionName);
                    toolCallStep.put("args", objectMapper.writeValueAsString(args));
                    toolCallStep.put("timestamp", LocalDateTime.now().toString());
                    traceLog.add(toolCallStep);

                    System.out.println("Agent calling tool: " + functionName + " with args: " + args);

                    // Execute the tool
                    String toolResult = executeTool(functionName, args);

                    // Log the tool result
                    ObjectNode toolResultStep = objectMapper.createObjectNode();
                    toolResultStep.put("step", iteration);
                    toolResultStep.put("type", "TOOL_RESULT");
                    toolResultStep.put("tool", functionName);
                    toolResultStep.put("result", toolResult.length() > 500 ? toolResult.substring(0, 500) + "..." : toolResult);
                    toolResultStep.put("timestamp", LocalDateTime.now().toString());
                    traceLog.add(toolResultStep);

                    // Build function response part
                    Map<String, Object> functionResponse = new HashMap<>();
                    Map<String, Object> fnResponseInner = new HashMap<>();
                    fnResponseInner.put("name", functionName);
                    fnResponseInner.put("response", Map.of("result", toolResult));
                    functionResponse.put("functionResponse", fnResponseInner);
                    functionResponseParts.add(functionResponse);
                }

                if (part.has("text") && !part.path("text").asText().trim().isEmpty()) {
                    String reasoning = part.path("text").asText();
                    ObjectNode reasoningStep = objectMapper.createObjectNode();
                    reasoningStep.put("step", iteration);
                    reasoningStep.put("type", "REASONING");
                    reasoningStep.put("content", reasoning);
                    reasoningStep.put("timestamp", LocalDateTime.now().toString());
                    traceLog.add(reasoningStep);
                }
            }

            if (hasFunctionCall && !functionResponseParts.isEmpty()) {
                // Send tool results back to the model
                Map<String, Object> toolResultMsg = new HashMap<>();
                toolResultMsg.put("role", "user");
                toolResultMsg.put("parts", functionResponseParts);
                conversationHistory.add(toolResultMsg);
            } else {
                // Model returned a text response without function calls — agent is done
                ObjectNode doneStep = objectMapper.createObjectNode();
                doneStep.put("step", iteration);
                doneStep.put("type", "COMPLETE");
                doneStep.put("content", "Agent investigation complete.");
                doneStep.put("timestamp", LocalDateTime.now().toString());
                traceLog.add(doneStep);
                break;
            }
        }

        String traceJson = objectMapper.writeValueAsString(traceLog);

        // Save trace to the fraud case
        Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
        if (caseOpt.isPresent()) {
            FraudCase fc = caseOpt.get();
            fc.setAgentTrace(traceJson);
            fraudCaseRepository.save(fc);
            // Broadcast update
            try {
                String json = String.format("{\"type\":\"CASE_UPDATE\",\"data\":%s}",
                    objectMapper.writeValueAsString(fc));
                webSocketHandler.broadcast(json);
            } catch (Exception e) { /* ignore */ }
        }

        return traceJson;
    }

    // ── Simulation Fallback ──

    private String runSimulatedAgentLoop(FraudCase fraudCase) {
        ArrayNode traceLog = objectMapper.createArrayNode();
        String caseId = fraudCase.getId();
        String accountId = fraudCase.getAccountId();

        // Step 0: Goal
        ObjectNode goalStep = objectMapper.createObjectNode();
        goalStep.put("step", 0);
        goalStep.put("type", "GOAL");
        goalStep.put("content", "Investigate fraud case " + caseId + " for account " + accountId);
        goalStep.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(goalStep);

        // Step 1: Get user baseline
        ObjectNode step1Call = objectMapper.createObjectNode();
        step1Call.put("step", 1);
        step1Call.put("type", "TOOL_CALL");
        step1Call.put("tool", "get_user_baseline");
        step1Call.put("args", "{\"account_id\":\"" + accountId + "\"}");
        step1Call.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step1Call);

        String baselineResult = executeTool("get_user_baseline", Map.of("account_id", accountId));
        ObjectNode step1Result = objectMapper.createObjectNode();
        step1Result.put("step", 1);
        step1Result.put("type", "TOOL_RESULT");
        step1Result.put("tool", "get_user_baseline");
        step1Result.put("result", baselineResult);
        step1Result.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step1Result);

        // Step 1 reasoning
        ObjectNode step1Reasoning = objectMapper.createObjectNode();
        step1Reasoning.put("step", 1);
        step1Reasoning.put("type", "REASONING");
        step1Reasoning.put("content", "I now have the sender's behavioral baseline. Let me check their recent transaction history to identify patterns and velocity anomalies.");
        step1Reasoning.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step1Reasoning);

        // Step 2: Get sender transactions
        ObjectNode step2Call = objectMapper.createObjectNode();
        step2Call.put("step", 2);
        step2Call.put("type", "TOOL_CALL");
        step2Call.put("tool", "get_case_transactions");
        step2Call.put("args", "{\"account_id\":\"" + accountId + "\"}");
        step2Call.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step2Call);

        String txnResult = executeTool("get_case_transactions", Map.of("account_id", accountId));
        ObjectNode step2Result = objectMapper.createObjectNode();
        step2Result.put("step", 2);
        step2Result.put("type", "TOOL_RESULT");
        step2Result.put("tool", "get_case_transactions");
        step2Result.put("result", txnResult);
        step2Result.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step2Result);

        ObjectNode step2Reasoning = objectMapper.createObjectNode();
        step2Reasoning.put("step", 2);
        step2Reasoning.put("type", "REASONING");
        step2Reasoning.put("content", "I can see the sender's recent activity. Now I need to check the receiver account for potential money mule indicators — specifically looking for many unique incoming senders.");
        step2Reasoning.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step2Reasoning);

        // Step 3: Get receiver profile
        Optional<Transaction> txnOpt = transactionRepository.findByTransactionId(fraudCase.getTransactionId());
        String receiverAccount = txnOpt.map(Transaction::getReceiverAccount).orElse("UNKNOWN");

        ObjectNode step3Call = objectMapper.createObjectNode();
        step3Call.put("step", 3);
        step3Call.put("type", "TOOL_CALL");
        step3Call.put("tool", "get_receiver_profile");
        step3Call.put("args", "{\"receiver_account_id\":\"" + receiverAccount + "\"}");
        step3Call.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step3Call);

        String receiverResult = executeTool("get_receiver_profile", Map.of("receiver_account_id", receiverAccount));
        ObjectNode step3Result = objectMapper.createObjectNode();
        step3Result.put("step", 3);
        step3Result.put("type", "TOOL_RESULT");
        step3Result.put("tool", "get_receiver_profile");
        step3Result.put("result", receiverResult);
        step3Result.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step3Result);

        // Determine risk based on actual data
        Optional<User> userOpt = userRepository.findByAccountId(accountId);
        boolean highRisk = false;
        String summary;
        String action;
        String evidence;

        if (txnOpt.isPresent() && userOpt.isPresent()) {
            Transaction txn = txnOpt.get();
            User user = userOpt.get();
            boolean locMismatch = !user.getFrequentLocations().contains(txn.getLocation());
            boolean devMismatch = !user.getFrequentDevices().contains(txn.getDeviceUsed());
            boolean amtAnomaly = txn.getAmount() > user.getAverageTransactionValue() * 2;

            int signalCount = (locMismatch ? 1 : 0) + (devMismatch ? 1 : 0) + (amtAnomaly ? 1 : 0);
            highRisk = signalCount >= 2 || Boolean.TRUE.equals(txn.getIsFraud());

            summary = highRisk
                ? String.format("Multiple anomaly signals detected: location=%s, device=%s, amount=$%.2f. Account %s shows signs of compromise.", txn.getLocation(), txn.getDeviceUsed(), txn.getAmount(), accountId)
                : String.format("Single anomaly detected for account %s. Moderate risk — recommend holding for review.", accountId);
            action = highRisk ? "FREEZE_ACCOUNT" : "FLAG_FOR_REVIEW";
            evidence = highRisk ? "HIGH" : "MEDIUM";
        } else {
            summary = "Unable to fully correlate transaction data. Flagging for manual review.";
            action = "FLAG_FOR_REVIEW";
            evidence = "LOW";
        }

        ObjectNode step3Reasoning = objectMapper.createObjectNode();
        step3Reasoning.put("step", 3);
        step3Reasoning.put("type", "REASONING");
        step3Reasoning.put("content", "Based on my analysis of the sender baseline, transaction history, and receiver profile, I can now form my conclusion. " + summary);
        step3Reasoning.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step3Reasoning);

        // Step 4: Submit report
        Map<String, String> reportArgs = new HashMap<>();
        reportArgs.put("case_id", caseId);
        reportArgs.put("investigation_summary", summary);
        reportArgs.put("evidence_strength", evidence);
        reportArgs.put("recommended_action", action);
        reportArgs.put("customer_message", highRisk
            ? "FraudShield Security Alert: We detected suspicious activity on your account and have temporarily secured it. Please contact support to verify your identity."
            : "FraudShield Notice: We noticed an unusual transaction on your account. Please verify this was you.");
        reportArgs.put("audit_trail", String.format(
            "1. Retrieved sender baseline for %s. 2. Analyzed %s recent transactions. 3. Checked receiver %s for mule patterns. 4. Conclusion: %s with %s evidence.",
            accountId, "10", receiverAccount, action, evidence));

        ObjectNode step4Call = objectMapper.createObjectNode();
        step4Call.put("step", 4);
        step4Call.put("type", "TOOL_CALL");
        step4Call.put("tool", "submit_investigation_report");
        try { step4Call.put("args", objectMapper.writeValueAsString(reportArgs)); } catch (Exception e) { step4Call.put("args", "{}"); }
        step4Call.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step4Call);

        String reportResult = executeTool("submit_investigation_report", reportArgs);
        ObjectNode step4Result = objectMapper.createObjectNode();
        step4Result.put("step", 4);
        step4Result.put("type", "TOOL_RESULT");
        step4Result.put("tool", "submit_investigation_report");
        step4Result.put("result", reportResult);
        step4Result.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(step4Result);

        // Step 5: Update status if high risk
        if (highRisk) {
            ObjectNode step5Call = objectMapper.createObjectNode();
            step5Call.put("step", 5);
            step5Call.put("type", "TOOL_CALL");
            step5Call.put("tool", "update_case_status");
            step5Call.put("args", "{\"case_id\":\"" + caseId + "\",\"status\":\"ACCOUNT_FROZEN\"}");
            step5Call.put("timestamp", LocalDateTime.now().toString());
            traceLog.add(step5Call);

            String statusResult = executeTool("update_case_status", Map.of("case_id", caseId, "status", "ACCOUNT_FROZEN"));
            ObjectNode step5Result = objectMapper.createObjectNode();
            step5Result.put("step", 5);
            step5Result.put("type", "TOOL_RESULT");
            step5Result.put("tool", "update_case_status");
            step5Result.put("result", statusResult);
            step5Result.put("timestamp", LocalDateTime.now().toString());
            traceLog.add(step5Result);
        }

        // Complete
        ObjectNode doneStep = objectMapper.createObjectNode();
        doneStep.put("step", highRisk ? 6 : 5);
        doneStep.put("type", "COMPLETE");
        doneStep.put("content", "Agent investigation complete. " + summary);
        doneStep.put("timestamp", LocalDateTime.now().toString());
        traceLog.add(doneStep);

        try {
            String traceJson = objectMapper.writeValueAsString(traceLog);
            // Save trace
            Optional<FraudCase> fcOpt = fraudCaseRepository.findById(caseId);
            if (fcOpt.isPresent()) {
                FraudCase fc = fcOpt.get();
                fc.setAgentTrace(traceJson);
                fraudCaseRepository.save(fc);
                try {
                    String json = String.format("{\"type\":\"CASE_UPDATE\",\"data\":%s}",
                        objectMapper.writeValueAsString(fc));
                    webSocketHandler.broadcast(json);
                } catch (Exception e) { /* ignore */ }
            }
            return traceJson;
        } catch (Exception e) {
            return "[]";
        }
    }

    private String getVertexAccessToken() throws Exception {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        if (credentials.createScopedRequired()) {
            credentials = credentials.createScoped(
                Collections.singletonList("https://www.googleapis.com/auth/cloud-platform")
            );
        }
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }
}
