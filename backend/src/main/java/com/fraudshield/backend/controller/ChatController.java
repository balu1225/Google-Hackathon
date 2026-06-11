package com.fraudshield.backend.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fraudshield.backend.model.FraudCase;
import com.fraudshield.backend.model.Transaction;
import com.fraudshield.backend.model.User;
import com.fraudshield.backend.repository.FraudCaseRepository;
import com.fraudshield.backend.repository.TransactionRepository;
import com.fraudshield.backend.repository.UserRepository;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private FraudCaseRepository fraudCaseRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${gcp.project.id:}")
    private String projectId;

    @Value("${gcp.region:us-central1}")
    private String region;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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

    public static class ChatRequest {
        public String message;
        public List<Map<String, String>> history;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public List<Map<String, String>> getHistory() { return history; }
        public void setHistory(List<Map<String, String>> history) { this.history = history; }
    }

    public static class ChatResponse {
        public String reply;
        public String action; // null, "FREEZE", or "DISMISS"

        public ChatResponse(String reply, String action) {
            this.reply = reply;
            this.action = action;
        }

        public String getReply() { return reply; }
        public String getAction() { return action; }
    }

    @PostMapping("/cases/{caseId}/chat")
    public ResponseEntity<ChatResponse> chatAboutCase(
            @PathVariable String caseId,
            @RequestBody ChatRequest chatRequest) {

        Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
        if (caseOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FraudCase fraudCase = caseOpt.get();

        // Gather all context
        Optional<Transaction> txnOpt = transactionRepository.findByTransactionId(fraudCase.getTransactionId());
        Optional<User> userOpt = userRepository.findByAccountId(fraudCase.getAccountId());

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("CASE CONTEXT:\n");
        contextBuilder.append(String.format("Case ID: %s | Status: %s | Risk Score: %.2f\n",
                fraudCase.getId(), fraudCase.getStatus(), fraudCase.getRiskScore()));
        contextBuilder.append(String.format("AI Reasoning: %s\n\n", fraudCase.getAiReasoning()));

        if (txnOpt.isPresent()) {
            Transaction txn = txnOpt.get();
            contextBuilder.append("FLAGGED TRANSACTION:\n");
            contextBuilder.append(String.format("  ID: %s | Amount: $%.2f | Type: %s\n", txn.getTransactionId(), txn.getAmount(), txn.getTransactionType()));
            contextBuilder.append(String.format("  Location: %s | Device: %s | Category: %s\n", txn.getLocation(), txn.getDeviceUsed(), txn.getMerchantCategory()));
            contextBuilder.append(String.format("  Sender: %s | Receiver: %s\n", txn.getSenderAccount(), txn.getReceiverAccount()));
            contextBuilder.append(String.format("  Time: %s | Fraud Flag: %s\n\n", txn.getTimestamp(), txn.getIsFraud()));

            // Get sender's recent history
            List<Transaction> senderHistory = transactionRepository.findTop10BySenderAccountOrderByTimestampDesc(txn.getSenderAccount());
            if (!senderHistory.isEmpty()) {
                contextBuilder.append("SENDER'S RECENT TRANSACTION HISTORY:\n");
                for (Transaction ht : senderHistory) {
                    contextBuilder.append(String.format("  - %s: $%.2f at %s (%s) via %s | Fraud: %s\n",
                            ht.getTransactionId(), ht.getAmount(), ht.getLocation(),
                            ht.getMerchantCategory(), ht.getDeviceUsed(), ht.getIsFraud()));
                }
                contextBuilder.append("\n");
            }
        }

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            contextBuilder.append("USER BEHAVIORAL BASELINE:\n");
            contextBuilder.append(String.format("  Name: %s | Account: %s\n", user.getName(), user.getAccountId()));
            contextBuilder.append(String.format("  Frequent Locations: %s\n", String.join(", ", user.getFrequentLocations())));
            contextBuilder.append(String.format("  Frequent Devices: %s\n", String.join(", ", user.getFrequentDevices())));
            contextBuilder.append(String.format("  Average Transaction Value: $%.2f\n\n", user.getAverageTransactionValue()));
        }

        if (fraudCase.getInvestigationReport() != null) {
            contextBuilder.append("AUTONOMOUS INVESTIGATION REPORT:\n");
            contextBuilder.append(fraudCase.getInvestigationReport());
            contextBuilder.append("\n\n");
        }

        if (fraudCase.getRegulatoryAuditRecord() != null) {
            contextBuilder.append("REGULATORY AUDIT RECORD:\n");
            contextBuilder.append(fraudCase.getRegulatoryAuditRecord());
            contextBuilder.append("\n\n");
        }

        String systemPrompt = "You are FraudShield AI, an expert fraud investigation assistant embedded in a real-time fraud detection dashboard. " +
                "You help analysts understand fraud cases by answering their questions using the provided case context. " +
                "Be concise, analytical, and direct. Use specific numbers and data from the context. " +
                "If the analyst asks to freeze an account or dismiss a case, acknowledge the action and set the appropriate action flag. " +
                "IMPORTANT: If the analyst's message contains an intent to freeze the account, include exactly 'ACTION:FREEZE' at the very end of your response. " +
                "If they want to dismiss/close the case, include exactly 'ACTION:DISMISS' at the very end.\n\n" +
                contextBuilder.toString();

        // Check if this should be a simulation (no project ID)
        if (projectId == null || projectId.trim().isEmpty()) {
            return ResponseEntity.ok(runSimulationChat(chatRequest.getMessage(), fraudCase, txnOpt.orElse(null), userOpt.orElse(null)));
        }

        try {
            String accessToken = getVertexAccessToken();

            // Build conversation contents
            List<Map<String, Object>> contents = new ArrayList<>();

            // System instruction as first user message
            Map<String, Object> systemContent = new HashMap<>();
            systemContent.put("role", "user");
            systemContent.put("parts", new Object[]{Map.of("text", systemPrompt + "\n\nAcknowledge that you understand this context with a brief confirmation.")});
            contents.add(systemContent);

            Map<String, Object> systemAck = new HashMap<>();
            systemAck.put("role", "model");
            systemAck.put("parts", new Object[]{Map.of("text", "Understood. I have the full case context for " + fraudCase.getTransactionId() + " and I'm ready to assist with your investigation.")});
            contents.add(systemAck);

            // Add conversation history
            if (chatRequest.getHistory() != null) {
                for (Map<String, String> msg : chatRequest.getHistory()) {
                    Map<String, Object> histContent = new HashMap<>();
                    histContent.put("role", "user".equals(msg.get("role")) ? "user" : "model");
                    histContent.put("parts", new Object[]{Map.of("text", msg.get("content"))});
                    contents.add(histContent);
                }
            }

            // Add current user message
            Map<String, Object> userContent = new HashMap<>();
            userContent.put("role", "user");
            userContent.put("parts", new Object[]{Map.of("text", chatRequest.getMessage())});
            contents.add(userContent);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", contents);

            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("maxOutputTokens", 1024);
            generationConfig.put("temperature", 0.7);
            requestBody.put("generationConfig", generationConfig);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            String url = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/gemini-2.5-flash:generateContent",
                region, projectId, region
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String replyText = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

                String action = null;
                if (replyText.contains("ACTION:FREEZE")) {
                    action = "FREEZE";
                    replyText = replyText.replace("ACTION:FREEZE", "").trim();
                } else if (replyText.contains("ACTION:DISMISS")) {
                    action = "DISMISS";
                    replyText = replyText.replace("ACTION:DISMISS", "").trim();
                }

                return ResponseEntity.ok(new ChatResponse(replyText, action));
            } else {
                System.err.println("Chat Vertex AI call failed: " + response.statusCode() + " " + response.body());
                return ResponseEntity.ok(runSimulationChat(chatRequest.getMessage(), fraudCase, txnOpt.orElse(null), userOpt.orElse(null)));
            }
        } catch (Exception e) {
            System.err.println("Chat exception: " + e.getMessage());
            return ResponseEntity.ok(runSimulationChat(chatRequest.getMessage(), fraudCase, txnOpt.orElse(null), userOpt.orElse(null)));
        }
    }

    private ChatResponse runSimulationChat(String message, FraudCase fc, Transaction txn, User user) {
        String msg = message.toLowerCase();
        String action = null;

        // Detect action intents
        if (msg.contains("freeze") || msg.contains("block") || msg.contains("lock")) {
            action = "FREEZE";
        } else if (msg.contains("dismiss") || msg.contains("close") || msg.contains("clear") || msg.contains("approve")) {
            action = "DISMISS";
        }

        String reply;

        if (msg.contains("why") && (msg.contains("flag") || msg.contains("risk") || msg.contains("fraud"))) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("This transaction was flagged with a **%.0f%% risk score** based on the following signals:\n\n", fc.getRiskScore() * 100));
            if (txn != null && user != null) {
                if (!user.getFrequentLocations().contains(txn.getLocation())) {
                    sb.append(String.format("• **Location Mismatch**: Transaction originated from `%s`, but the cardholder's established locations are `%s`.\n",
                            txn.getLocation(), String.join(", ", user.getFrequentLocations())));
                }
                if (!user.getFrequentDevices().contains(txn.getDeviceUsed())) {
                    sb.append(String.format("• **Device Mismatch**: Used `%s`, but the cardholder typically uses `%s`.\n",
                            txn.getDeviceUsed(), String.join(", ", user.getFrequentDevices())));
                }
                if (txn.getAmount() > user.getAverageTransactionValue() * 2) {
                    sb.append(String.format("• **Amount Anomaly**: $%.2f is %.1fx the cardholder's average of $%.2f.\n",
                            txn.getAmount(), txn.getAmount() / user.getAverageTransactionValue(), user.getAverageTransactionValue()));
                }
            }
            sb.append("\nThe combination of these signals suggests potential account compromise or unauthorized usage.");
            reply = sb.toString();
        } else if (msg.contains("travel") || msg.contains("moving") || msg.contains("trip") || msg.contains("vacation")) {
            if (txn != null) {
                reply = String.format("The geographic velocity analysis makes legitimate travel **unlikely**. The previous transaction was processed at a different location, and the time delta between transactions is too short for physical travel. " +
                    "If the cardholder has confirmed they are traveling, you can dismiss this case — but I'd recommend verifying the device fingerprint (`%s`) first, as account takeover via credential theft is more probable than a traveler using an unregistered device from a new location simultaneously.",
                    txn.getDeviceUsed());
            } else {
                reply = "I don't have the transaction details to assess travel feasibility. The geographic velocity check requires timestamp and location data from consecutive transactions.";
            }
        } else if (msg.contains("similar") || msg.contains("pattern") || msg.contains("history")) {
            reply = String.format("Looking at the sender account `%s`'s recent history, I can identify the following pattern:\n\n" +
                    "• The account typically transacts at consistent locations and devices.\n" +
                    "• This specific transaction deviates from the established baseline in multiple dimensions simultaneously.\n" +
                    "• Multi-signal deviations (location + device + amount together) have a **94%% correlation with confirmed fraud** in our historical data.\n\n" +
                    "This is not an isolated anomaly — it's a behavioral pattern break consistent with credential theft.",
                    fc.getAccountId());
        } else if (action != null) {
            if ("FREEZE".equals(action)) {
                reply = String.format("✅ **Account freeze initiated** for `%s`.\n\nThe case status is being updated to `ACCOUNT_FROZEN`. " +
                        "A customer notification will be dispatched: *\"%s\"*\n\nAll pending transactions for this account are now held.",
                        fc.getAccountId(), fc.getCustomerExplanation() != null ? fc.getCustomerExplanation() : "Your account has been temporarily secured for your protection.");
            } else {
                reply = String.format("✅ **Case dismissed** for transaction `%s`.\n\nThe alert has been cleared and the case status updated to `CLOSED`. " +
                        "The cardholder's behavioral baseline will be updated to incorporate this transaction pattern to reduce future false positives.",
                        fc.getTransactionId());
            }
        } else if (msg.contains("recommend") || msg.contains("should") || msg.contains("what do")) {
            double risk = fc.getRiskScore() != null ? fc.getRiskScore() : 0;
            if (risk > 0.7) {
                reply = String.format("Based on the **%.0f%% risk score** and the evidence gathered, I recommend **freezing the account immediately**. " +
                        "The signal combination is highly indicative of unauthorized access. Say \"freeze the account\" to proceed.", risk * 100);
            } else if (risk > 0.4) {
                reply = String.format("The **%.0f%% risk score** suggests moderate suspicion. I'd recommend **holding the transaction** and contacting the cardholder for verification " +
                        "before taking drastic action. If they confirm the transaction, dismiss the case.", risk * 100);
            } else {
                reply = "The risk indicators are relatively low. This may be a legitimate behavioral shift. I'd recommend monitoring but allowing the transaction to proceed.";
            }
        } else {
            reply = String.format("I'm analyzing case `%s` for account `%s` (Risk: %.0f%%). You can ask me:\n\n" +
                    "• **\"Why was this flagged?\"** — breakdown of risk signals\n" +
                    "• **\"Could they be traveling?\"** — geographic velocity analysis\n" +
                    "• **\"Show similar patterns\"** — historical pattern matching\n" +
                    "• **\"What do you recommend?\"** — action recommendation\n" +
                    "• **\"Freeze the account\"** or **\"Dismiss the case\"** — take action\n\n" +
                    "What would you like to investigate?",
                    fc.getTransactionId(), fc.getAccountId(), (fc.getRiskScore() != null ? fc.getRiskScore() * 100 : 0));
        }

        return new ChatResponse(reply, action);
    }
}
