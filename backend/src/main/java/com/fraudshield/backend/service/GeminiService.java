package com.fraudshield.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fraudshield.backend.model.Transaction;
import com.fraudshield.backend.model.User;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class GeminiService {

    @Value("${gcp.project.id:}")
    private String projectId;

    @Value("${gcp.region:us-central1}")
    private String region;

    private String getVertexAccessToken() throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
                if (credentials.createScopedRequired()) {
                    credentials = credentials.createScoped(
                        Collections.singletonList("https://www.googleapis.com/auth/cloud-platform")
                    );
                }
                credentials.refreshIfExpired();
                return credentials.getAccessToken().getTokenValue();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception("GCP credentials unavailable (metadata server unreachable) — using simulation fallback");
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class GeminiAnalysisResult {
        public Double riskScore;
        public String confidenceLevel;
        public String[] primarySignals;
        public String detailedReasoning;
        public String recommendedAction;
        public String customerExplanation;
        public String regulatoryAuditRecord;

        public Double getRiskScore() { return riskScore; }
        public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }

        public String getConfidenceLevel() { return confidenceLevel; }
        public void setConfidenceLevel(String confidenceLevel) { this.confidenceLevel = confidenceLevel; }

        public String[] getPrimarySignals() { return primarySignals; }
        public void setPrimarySignals(String[] primarySignals) { this.primarySignals = primarySignals; }

        public String getDetailedReasoning() { return detailedReasoning; }
        public void setDetailedReasoning(String detailedReasoning) { this.detailedReasoning = detailedReasoning; }

        public String getRecommendedAction() { return recommendedAction; }
        public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }

        public String getCustomerExplanation() { return customerExplanation; }
        public void setCustomerExplanation(String customerExplanation) { this.customerExplanation = customerExplanation; }

        public String getRegulatoryAuditRecord() { return regulatoryAuditRecord; }
        public void setRegulatoryAuditRecord(String regulatoryAuditRecord) { this.regulatoryAuditRecord = regulatoryAuditRecord; }
    }

    public static class ProfileUpdateResult {
        public boolean shouldUpdate;
        public String[] addLocations;
        public String[] removeLocations;
        public Double updatedAverageValue;
        public String[] addDevices;
        public String[] removeDevices;
        public String detailedReasoning;

        public boolean isShouldUpdate() { return shouldUpdate; }
        public void setShouldUpdate(boolean shouldUpdate) { this.shouldUpdate = shouldUpdate; }

        public String[] getAddLocations() { return addLocations; }
        public void setAddLocations(String[] addLocations) { this.addLocations = addLocations; }

        public String[] getRemoveLocations() { return removeLocations; }
        public void setRemoveLocations(String[] removeLocations) { this.removeLocations = removeLocations; }

        public Double getUpdatedAverageValue() { return updatedAverageValue; }
        public void setUpdatedAverageValue(Double updatedAverageValue) { this.updatedAverageValue = updatedAverageValue; }

        public String[] getAddDevices() { return addDevices; }
        public void setAddDevices(String[] addDevices) { this.addDevices = addDevices; }

        public String[] getRemoveDevices() { return removeDevices; }
        public void setRemoveDevices(String[] removeDevices) { this.removeDevices = removeDevices; }

        public String getDetailedReasoning() { return detailedReasoning; }
        public void setDetailedReasoning(String detailedReasoning) { this.detailedReasoning = detailedReasoning; }
    }

    public GeminiAnalysisResult analyzeTransaction(Transaction txn, User user, List<Transaction> recentHistory) {
        long start = System.currentTimeMillis();
        System.out.println("DEBUG: Entering analyzeTransaction for transaction " + txn.getTransactionId());
        // Fallback check if Project ID is empty/not configured
        if (projectId == null || projectId.trim().isEmpty()) {
            System.out.println("Warning: gcp.project.id is not configured. Running fallback simulation analysis.");
            return runSimulationAnalysis(txn, user, recentHistory);
        }

        try {
            // Retrieve OAuth access token from GCP credentials
            String accessToken;
            try {
                long tokenStart = System.currentTimeMillis();
                accessToken = getVertexAccessToken();
                System.out.println("DEBUG: Got Vertex AI access token in " + (System.currentTimeMillis() - tokenStart) + " ms");
            } catch (Exception authEx) {
                System.out.println("Warning: Failed to fetch Vertex AI access token. Check your gcloud authentication. Running fallback simulation. Error: " + authEx.getMessage());
                return runSimulationAnalysis(txn, user, recentHistory);
            }

            StringBuilder historyBuilder = new StringBuilder();
            if (recentHistory == null || recentHistory.isEmpty()) {
                historyBuilder.append("  - No recent transaction history found.\n");
            } else {
                for (Transaction prevTxn : recentHistory) {
                    historyBuilder.append(String.format(
                        "  - Txn ID: %s | Time: %s | Amount: $%.2f | Location: %s | Device: %s | Fraud Flagged: %s\n",
                        prevTxn.getTransactionId(),
                        prevTxn.getTimestamp().toString(),
                        prevTxn.getAmount(),
                        prevTxn.getLocation(),
                        prevTxn.getDeviceUsed(),
                        prevTxn.getIsFraud()
                    ));
                }
            }

            String prompt = String.format(
                "You are an expert fraud detection AI. Analyze this financial transaction against the user's historical baseline profile AND their recent transaction history, then output if it is fraud, risk details, and recommended action.\n\n" +
                "User Name: %s\n" +
                "Account ID: %s\n" +
                "User Baseline Profile:\n" +
                "  - Frequent Locations: %s\n" +
                "  - Frequent Devices: %s\n" +
                "  - Average Transaction Value: $%.2f\n\n" +
                "Recent Transactions History (Most Recent First):\n%s\n" +
                "Transaction under investigation:\n" +
                "  - Transaction ID: %s\n" +
                "  - Timestamp: %s\n" +
                "  - Amount: $%.2f\n" +
                "  - Type: %s\n" +
                "  - Location: %s\n" +
                "  - Device Used: %s\n" +
                "  - Merchant Category: %s\n" +
                "  - Receiver Account: %s\n\n" +
                "CRITICAL: Evaluate geographic velocity (e.g. is it physically possible to travel between the locations of the recent transactions and this new transaction in the elapsed time?).\n" +
                "COMPLIANCE REQUIREMENTS:\n" +
                "1. Provide a 'customerExplanation' explaining politely and non-technically in plain English (max 3 sentences) why we've temporarily held/flagged their transaction for security.\n" +
                "2. Provide a 'regulatoryAuditRecord' as a JSON-formatted string including the decision timestamp, a list of violated baseline rules, geographic velocity test parameters, and recommended action to serve as a compliant audit trail.",
                user.getName(), user.getAccountId(),
                String.join(", ", user.getFrequentLocations()),
                String.join(", ", user.getFrequentDevices()),
                user.getAverageTransactionValue(),
                historyBuilder.toString(),
                txn.getTransactionId(),
                txn.getTimestamp().toString(),
                txn.getAmount(),
                txn.getTransactionType(),
                txn.getLocation(),
                txn.getDeviceUsed(),
                txn.getMerchantCategory(),
                txn.getReceiverAccount()
            );

            // Construct JSON request body
            Map<String, Object> requestBody = new HashMap<>();
            
            // contents
            Map<String, Object> part = new HashMap<>();
            part.put("text", prompt);
            Map<String, Object> content = new HashMap<>();
            content.put("role", "user");
            content.put("parts", new Object[]{part});
            requestBody.put("contents", new Object[]{content});

            // generationConfig
            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("responseMimeType", "application/json");

            // responseSchema
            Map<String, Object> responseSchema = new HashMap<>();
            responseSchema.put("type", "OBJECT");
            
            Map<String, Object> properties = new HashMap<>();
            properties.put("riskScore", Map.of(
                "type", "NUMBER",
                "description", "The evaluated fraud risk score, as a decimal between 0.0 (safe) and 1.0 (highest risk)"
            ));
            properties.put("confidenceLevel", Map.of("type", "STRING"));
            properties.put("primarySignals", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
            properties.put("detailedReasoning", Map.of("type", "STRING"));
            properties.put("recommendedAction", Map.of("type", "STRING"));
            properties.put("customerExplanation", Map.of("type", "STRING"));
            properties.put("regulatoryAuditRecord", Map.of("type", "STRING"));

            responseSchema.put("properties", properties);
            responseSchema.put("required", new String[]{"riskScore", "confidenceLevel", "primarySignals", "detailedReasoning", "recommendedAction", "customerExplanation", "regulatoryAuditRecord"});

            generationConfig.put("responseSchema", responseSchema);
            requestBody.put("generationConfig", generationConfig);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            // Construct Vertex AI endpoint URL
            String url = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/gemini-2.5-flash:generateContent",
                region, projectId, region
            );

            System.out.println("DEBUG: Sending request to Vertex AI at " + url);
            long httpStart = System.currentTimeMillis();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("DEBUG: Received response from Vertex AI in " + (System.currentTimeMillis() - httpStart) + " ms with status: " + response.statusCode());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode candidateText = root.path("candidates").get(0).path("content").path("parts").get(0).path("text");
                String jsonText = candidateText.asText();
                return objectMapper.readValue(jsonText, GeminiAnalysisResult.class);
            } else {
                System.err.println("Vertex AI Gemini call failed with status: " + response.statusCode() + " response: " + response.body());
                return runSimulationAnalysis(txn, user, recentHistory);
            }

        } catch (Exception e) {
            System.err.println("Exception calling Vertex AI: " + e.getMessage());
            e.printStackTrace();
            return runSimulationAnalysis(txn, user, recentHistory);
        }
    }

    private GeminiAnalysisResult runSimulationAnalysis(Transaction txn, User user, List<Transaction> recentHistory) {
        GeminiAnalysisResult result = new GeminiAnalysisResult();
        boolean locationMismatch = !user.getFrequentLocations().contains(txn.getLocation());
        boolean deviceMismatch = !user.getFrequentDevices().contains(txn.getDeviceUsed());
        boolean valueAnomaly = txn.getAmount() > (user.getAverageTransactionValue() * 2);

        double score = 0.1;
        var signals = new java.util.ArrayList<String>();
        var reasoning = new StringBuilder("Simulation Anomaly Analyzer flagged this transaction. ");

        // Velocity checking
        boolean velocityAnomaly = false;
        long durationMinutes = 0;
        String prevLoc = "";
        if (recentHistory != null && !recentHistory.isEmpty()) {
            Transaction prev = recentHistory.get(0);
            durationMinutes = Math.abs(java.time.Duration.between(prev.getTimestamp(), txn.getTimestamp()).toMinutes());
            prevLoc = prev.getLocation();
            boolean locDiff = !prev.getLocation().equalsIgnoreCase(txn.getLocation());
            // If location changed and time difference is less than 2 hours (120 minutes)
            if (locDiff && durationMinutes < 120) {
                velocityAnomaly = true;
            }
        }

        if (velocityAnomaly) {
            score += 0.45;
            signals.add(String.format("Velocity attack detected (Impossible travel: %s to %s in %d mins)", prevLoc, txn.getLocation(), durationMinutes));
            reasoning.append(String.format("Geographic velocity check failed. A transaction was completed in %s, and just %d minutes later, a transaction occurred in %s. Travel between these locations is physically impossible in the elapsed time. ", prevLoc, durationMinutes, txn.getLocation()));
        }
        if (locationMismatch) {
            score += 0.35;
            signals.add("Location mismatch (" + txn.getLocation() + " is not in frequent locations)");
            reasoning.append("The transaction location '").append(txn.getLocation()).append("' is not registered in the user's baseline frequent locations. ");
        }
        if (deviceMismatch) {
            score += 0.25;
            signals.add("Device mismatch (" + txn.getDeviceUsed() + " is not a frequent device)");
            reasoning.append("The transaction was initiated from a '").append(txn.getDeviceUsed()).append("' device, which is not commonly used by this user. ");
        }
        if (valueAnomaly) {
            score += 0.3;
            signals.add("High transaction value ($" + txn.getAmount() + " vs average $" + user.getAverageTransactionValue() + ")");
            reasoning.append("The transaction value of $").append(txn.getAmount()).append(" is significantly higher than the user's average transaction amount of $").append(user.getAverageTransactionValue()).append(". ");
        }

        result.riskScore = Math.min(score, 1.0);
        result.confidenceLevel = result.riskScore > 0.7 ? "HIGH" : (result.riskScore > 0.4 ? "MEDIUM" : "LOW");
        result.primarySignals = signals.toArray(new String[0]);
        result.detailedReasoning = reasoning.toString();
        result.recommendedAction = result.riskScore > 0.7 ? "FREEZE_ACCOUNT" : (result.riskScore > 0.4 ? "FLAG_FOR_REVIEW" : "ALLOW");

        result.customerExplanation = result.riskScore > 0.4 
            ? "Hello from FraudShield Security. We detected an unusual transaction on your card and have temporarily held it for your protection. Please contact support or confirm this transaction."
            : "This transaction matches your typical behavioral profile and has been authorized.";
            
        java.util.Map<String, Object> audit = new java.util.HashMap<>();
        audit.put("timestamp", java.time.LocalDateTime.now().toString());
        audit.put("decisionEngine", "FraudShield Simulation 1.0");
        audit.put("evaluatedRiskScore", result.riskScore);
        audit.put("threatSignals", signals);
        audit.put("complianceStatus", result.riskScore > 0.7 ? "SUSPENDED" : "COMPLIANT");
        audit.put("recommendedAction", result.recommendedAction);
        
        try {
            result.regulatoryAuditRecord = objectMapper.writeValueAsString(audit);
        } catch (Exception e) {
            result.regulatoryAuditRecord = "{\"error\":\"Failed to serialize simulated audit log\"}";
        }

        return result;
    }

    public ProfileUpdateResult updateUserProfile(User user, List<Transaction> recentHistory) {
        if (projectId == null || projectId.trim().isEmpty()) {
            return runSimulationProfileUpdate(user, recentHistory);
        }

        try {
            String accessToken;
            try {
                accessToken = getVertexAccessToken();
            } catch (Exception authEx) {
                System.out.println("Warning: Failed to fetch token for profile update. Running simulation. Error: " + authEx.getMessage());
                return runSimulationProfileUpdate(user, recentHistory);
            }

            StringBuilder historyBuilder = new StringBuilder();
            if (recentHistory == null || recentHistory.isEmpty()) {
                historyBuilder.append("  - No recent clean transaction history found.\n");
            } else {
                for (Transaction prevTxn : recentHistory) {
                    historyBuilder.append(String.format(
                        "  - Txn ID: %s | Time: %s | Amount: $%.2f | Location: %s | Device: %s\n",
                        prevTxn.getTransactionId(),
                        prevTxn.getTimestamp().toString(),
                        prevTxn.getAmount(),
                        prevTxn.getLocation(),
                        prevTxn.getDeviceUsed()
                    ));
                }
            }

            String prompt = String.format(
                "You are an expert user behavior profiling AI. Review a customer's historical behavioral baseline profile against their recent transaction history (last 10 clean transactions) to determine if their behavior baseline has shifted. " +
                "For example: has a new location or device been used multiple times (e.g., 3+ times)? Has their average transaction value changed significantly? " +
                "Provide updates if appropriate (shouldUpdate = true), else return shouldUpdate = false.\n\n" +
                "User Name: %s\n" +
                "Account ID: %s\n" +
                "Current Baseline Profile:\n" +
                "  - Frequent Locations: %s\n" +
                "  - Frequent Devices: %s\n" +
                "  - Average Transaction Value: $%.2f\n\n" +
                "Recent Transactions History (Most Recent First):\n%s\n\n" +
                "INSTRUCTIONS:\n" +
                "1. If a new location or device has been used 3+ times, recommend adding it via 'addLocations' or 'addDevices'.\n" +
                "2. If an existing location or device has not appeared in recent history and is inactive, you may optionally recommend removing it.\n" +
                "3. Calculate a new average transaction value on the rolling recent window and recommend it via 'updatedAverageValue'.\n" +
                "4. Output a detailedReasoning explaining the changes.",
                user.getName(), user.getAccountId(),
                String.join(", ", user.getFrequentLocations()),
                String.join(", ", user.getFrequentDevices()),
                user.getAverageTransactionValue(),
                historyBuilder.toString()
            );

            Map<String, Object> requestBody = new HashMap<>();
            
            Map<String, Object> part = new HashMap<>();
            part.put("text", prompt);
            Map<String, Object> content = new HashMap<>();
            content.put("role", "user");
            content.put("parts", new Object[]{part});
            requestBody.put("contents", new Object[]{content});

            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("responseMimeType", "application/json");

            Map<String, Object> responseSchema = new HashMap<>();
            responseSchema.put("type", "OBJECT");
            
            Map<String, Object> properties = new HashMap<>();
            properties.put("shouldUpdate", Map.of("type", "BOOLEAN"));
            properties.put("addLocations", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
            properties.put("removeLocations", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
            properties.put("updatedAverageValue", Map.of("type", "NUMBER"));
            properties.put("addDevices", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
            properties.put("removeDevices", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
            properties.put("detailedReasoning", Map.of("type", "STRING"));

            responseSchema.put("properties", properties);
            responseSchema.put("required", new String[]{
                "shouldUpdate", "addLocations", "removeLocations", "updatedAverageValue", "addDevices", "removeDevices", "detailedReasoning"
            });

            generationConfig.put("responseSchema", responseSchema);
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
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode candidateText = root.path("candidates").get(0).path("content").path("parts").get(0).path("text");
                String jsonText = candidateText.asText();
                return objectMapper.readValue(jsonText, ProfileUpdateResult.class);
            } else {
                System.err.println("Vertex AI Profile Update call failed with status: " + response.statusCode() + " response: " + response.body());
                return runSimulationProfileUpdate(user, recentHistory);
            }
        } catch (Exception e) {
            System.err.println("Exception calling Vertex AI for profile update: " + e.getMessage());
            return runSimulationProfileUpdate(user, recentHistory);
        }
    }

    private ProfileUpdateResult runSimulationProfileUpdate(User user, List<Transaction> recentHistory) {
        ProfileUpdateResult result = new ProfileUpdateResult();
        result.shouldUpdate = false;
        result.addLocations = new String[0];
        result.removeLocations = new String[0];
        result.addDevices = new String[0];
        result.removeDevices = new String[0];
        result.updatedAverageValue = user.getAverageTransactionValue();
        result.detailedReasoning = "No behavior baseline drift detected.";

        if (recentHistory == null || recentHistory.isEmpty()) {
            return result;
        }

        Map<String, Integer> locCounts = new HashMap<>();
        Map<String, Integer> devCounts = new HashMap<>();
        double sum = 0;

        for (Transaction t : recentHistory) {
            sum += t.getAmount();
            if (t.getLocation() != null) {
                locCounts.put(t.getLocation(), locCounts.getOrDefault(t.getLocation(), 0) + 1);
            }
            if (t.getDeviceUsed() != null) {
                devCounts.put(t.getDeviceUsed(), devCounts.getOrDefault(t.getDeviceUsed(), 0) + 1);
            }
        }

        double newAvg = sum / recentHistory.size();
        
        java.util.List<String> locsToAdd = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> entry : locCounts.entrySet()) {
            String loc = entry.getKey();
            if (entry.getValue() >= 3 && !user.getFrequentLocations().contains(loc)) {
                locsToAdd.add(loc);
            }
        }

        java.util.List<String> devsToAdd = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> entry : devCounts.entrySet()) {
            String dev = entry.getKey();
            if (entry.getValue() >= 3 && !user.getFrequentDevices().contains(dev)) {
                devsToAdd.add(dev);
            }
        }

        boolean avgChanged = Math.abs(newAvg - user.getAverageTransactionValue()) > (user.getAverageTransactionValue() * 0.1);

        if (!locsToAdd.isEmpty() || !devsToAdd.isEmpty() || avgChanged) {
            result.shouldUpdate = true;
            result.addLocations = locsToAdd.toArray(new String[0]);
            result.addDevices = devsToAdd.toArray(new String[0]);
            result.updatedAverageValue = newAvg;
            
            StringBuilder sb = new StringBuilder("Drift detected. ");
            if (!locsToAdd.isEmpty()) {
                sb.append("Adding new frequent locations: ").append(String.join(", ", locsToAdd)).append(". ");
            }
            if (!devsToAdd.isEmpty()) {
                sb.append("Adding new frequent devices: ").append(String.join(", ", devsToAdd)).append(". ");
            }
            if (avgChanged) {
                sb.append(String.format("Updating average spending baseline from $%.2f to $%.2f. ", user.getAverageTransactionValue(), newAvg));
            }
            result.detailedReasoning = sb.toString();
        }

        return result;
    }

    public String runAgentInvestigation(User user, Transaction t, List<Transaction> senderHistory, List<Transaction> receiverHistory) {
        if (projectId == null || projectId.trim().isEmpty()) {
            return runSimulationAgentInvestigation(user, t, senderHistory, receiverHistory);
        }

        try {
            String accessToken;
            try {
                accessToken = getVertexAccessToken();
            } catch (Exception authEx) {
                System.out.println("Warning: Failed to fetch token for agent investigation. Running simulation. Error: " + authEx.getMessage());
                return runSimulationAgentInvestigation(user, t, senderHistory, receiverHistory);
            }

            StringBuilder senderHistoryBuilder = new StringBuilder();
            if (senderHistory == null || senderHistory.isEmpty()) {
                senderHistoryBuilder.append("  - No recent clean sender transaction history found.\n");
            } else {
                for (Transaction prevTxn : senderHistory) {
                    senderHistoryBuilder.append(String.format(
                        "  - Txn ID: %s | Time: %s | Amount: $%.2f | Location: %s | Device: %s\n",
                        prevTxn.getTransactionId(),
                        prevTxn.getTimestamp().toString(),
                        prevTxn.getAmount(),
                        prevTxn.getLocation(),
                        prevTxn.getDeviceUsed()
                    ));
                }
            }

            StringBuilder receiverHistoryBuilder = new StringBuilder();
            if (receiverHistory == null || receiverHistory.isEmpty()) {
                receiverHistoryBuilder.append("  - No recent receiver incoming transaction history found.\n");
            } else {
                for (Transaction prevTxn : receiverHistory) {
                    receiverHistoryBuilder.append(String.format(
                        "  - Txn ID: %s | Time: %s | Amount: $%.2f | Sender: %s | Location: %s\n",
                        prevTxn.getTransactionId(),
                        prevTxn.getTimestamp().toString(),
                        prevTxn.getAmount(),
                        prevTxn.getSenderAccount(),
                        prevTxn.getLocation()
                    ));
                }
            }

            String prompt = String.format(
                "You are an Autonomous Fraud Investigation Agent. Review the following transaction under investigation and the consolidated evidence dump:\n\n" +
                "TRANSACTION UNDER INVESTIGATION:\n" +
                "  - Transaction ID: %s\n" +
                "  - Sender Account: %s\n" +
                "  - Receiver Account: %s\n" +
                "  - Amount: $%.2f\n" +
                "  - Location: %s\n" +
                "  - Device Used: %s\n" +
                "  - Timestamp: %s\n\n" +
                "EVIDENCE - SENDER PROFILE BASELINE:\n" +
                "  - Frequent Locations: %s\n" +
                "  - Frequent Devices: %s\n" +
                "  - Average Transaction Value: $%.2f\n\n" +
                "EVIDENCE - SENDER TRANSACTION HISTORY:\n%s\n" +
                "EVIDENCE - RECEIVER TRANSACTION HISTORY:\n%s\n\n" +
                "TASK:\n" +
                "Conduct a deep agentic case analysis. Cross-reference the transaction details with the sender's history and the receiver's history (e.g., check for potential money mule behavior if the receiver has many deposits from multiple accounts or unusual transaction spikes). " +
                "Return a structured JSON output representing the investigation report.\n\n" +
                "REQUIRED JSON STRUCTURE:\n" +
                "{\n" +
                "  \"investigationSummary\": \"Concise synthesis of the case explaining whether this represents normal activity, compromised credentials, or a money mule ring.\",\n" +
                "  \"evidenceStrength\": \"HIGH\" | \"MEDIUM\" | \"LOW\",\n" +
                "  \"recommendedAction\": \"FREEZE_ACCOUNT\" | \"FLAG_FOR_REVIEW\" | \"ALLOW\",\n" +
                "  \"customerMessage\": \"SMS notification drafted to the customer.\",\n" +
                "  \"auditTrail\": \"Step-by-step log of facts analyzed and velocity/network rules verified during the agent run.\"\n" +
                "}",
                t.getTransactionId(), t.getSenderAccount(), t.getReceiverAccount(), t.getAmount(), t.getLocation(), t.getDeviceUsed(), t.getTimestamp().toString(),
                String.join(", ", user.getFrequentLocations()), String.join(", ", user.getFrequentDevices()), user.getAverageTransactionValue(),
                senderHistoryBuilder.toString(), receiverHistoryBuilder.toString()
            );

            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> part = new HashMap<>();
            part.put("text", prompt);
            Map<String, Object> content = new HashMap<>();
            content.put("role", "user");
            content.put("parts", new Object[]{part});
            requestBody.put("contents", new Object[]{content});

            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("responseMimeType", "application/json");

            Map<String, Object> responseSchema = new HashMap<>();
            responseSchema.put("type", "OBJECT");
            
            Map<String, Object> properties = new HashMap<>();
            properties.put("investigationSummary", Map.of("type", "STRING"));
            properties.put("evidenceStrength", Map.of("type", "STRING"));
            properties.put("recommendedAction", Map.of("type", "STRING"));
            properties.put("customerMessage", Map.of("type", "STRING"));
            properties.put("auditTrail", Map.of("type", "STRING"));

            responseSchema.put("properties", properties);
            responseSchema.put("required", new String[]{
                "investigationSummary", "evidenceStrength", "recommendedAction", "customerMessage", "auditTrail"
            });

            generationConfig.put("responseSchema", responseSchema);
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
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                return root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
            } else {
                System.err.println("Vertex AI Agent Investigation call failed with status: " + response.statusCode() + " response: " + response.body());
                return runSimulationAgentInvestigation(user, t, senderHistory, receiverHistory);
            }
        } catch (Exception e) {
            System.err.println("Exception calling Vertex AI for agent investigation: " + e.getMessage());
            return runSimulationAgentInvestigation(user, t, senderHistory, receiverHistory);
        }
    }

    private String runSimulationAgentInvestigation(User user, Transaction t, List<Transaction> senderHistory, List<Transaction> receiverHistory) {
        boolean locationMismatch = !user.getFrequentLocations().contains(t.getLocation());
        boolean deviceMismatch = !user.getFrequentDevices().contains(t.getDeviceUsed());
        boolean amountAnomaly = t.getAmount() > (user.getAverageTransactionValue() * 2.0);

        String summary;
        String action;
        String strength;
        String message;
        StringBuilder audit = new StringBuilder();

        audit.append(String.format("1. Fetch sender baseline: frequentLocations=%s, frequentDevices=%s, averageValue=$%.2f. ", 
            user.getFrequentLocations(), user.getFrequentDevices(), user.getAverageTransactionValue()));
        audit.append(String.format("2. Inspect transaction %s: Loc=%s, Dev=%s, Amt=$%.2f. ", 
            t.getTransactionId(), t.getLocation(), t.getDeviceUsed(), t.getAmount()));

        int distinctSendersToReceiver = 0;
        if (receiverHistory != null) {
            java.util.Set<String> senders = new java.util.HashSet<>();
            for (Transaction prev : receiverHistory) {
                if (prev.getSenderAccount() != null) {
                    senders.add(prev.getSenderAccount());
                }
            }
            distinctSendersToReceiver = senders.size();
            audit.append(String.format("3. Fetch receiver incoming history: found %d transactions from %d unique senders. ", 
                receiverHistory.size(), distinctSendersToReceiver));
        } else {
            audit.append("3. Fetch receiver incoming history: no historical records found. ");
        }

        if (distinctSendersToReceiver >= 3) {
            summary = String.format("High risk account takeover and money mule chain detected. Receiver account %s has received incoming transactions from %d unique accounts in recent hours, indicating potential syndicate payout activity.", 
                t.getReceiverAccount(), distinctSendersToReceiver);
            action = "FREEZE_ACCOUNT";
            strength = "HIGH";
            message = "FraudShield Security Alert: Your account is locked due to high-risk transfer activity matching money mule network patterns. Please contact fraud ops.";
            audit.append("Decision rule: receiver has transactions from 3+ unique accounts -> mule network pattern matched. Recommended Action: FREEZE_ACCOUNT.");
        } else if (Boolean.TRUE.equals(t.getIsFraud()) || locationMismatch && deviceMismatch) {
            summary = String.format("Suspicious transaction detected for account %s. Transaction executed from an unrecognized location (%s) and device (%s) simultaneously, indicating potential account takeover.", 
                t.getSenderAccount(), t.getLocation(), t.getDeviceUsed());
            action = "FREEZE_ACCOUNT";
            strength = "HIGH";
            message = "FraudShield Alert: We blocked a transfer from a new device/location and locked your account. If this was you, call support.";
            audit.append("Decision rule: double mismatch (location + device) -> critical threat. Recommended Action: FREEZE_ACCOUNT.");
        } else if (locationMismatch || deviceMismatch || amountAnomaly) {
            summary = String.format("A deviation was flagged on account %s. Transaction amount $%.2f exceeds average baseline ($%.2f) or uses an unconfirmed device/location.", 
                t.getSenderAccount(), t.getAmount(), user.getAverageTransactionValue());
            action = "FLAG_FOR_REVIEW";
            strength = "MEDIUM";
            message = "FraudShield Security Notice: We detected an unusual transaction and held it for review. Verify this transaction to release the hold.";
            audit.append("Decision rule: single mismatch (location, device, or amount) -> medium threat. Recommended Action: FLAG_FOR_REVIEW.");
        } else {
            summary = "No anomalous patterns detected. The transaction aligns with the user's established historical baseline behavior.";
            action = "ALLOW";
            strength = "LOW";
            message = "Transaction authorized.";
            audit.append("Decision rule: transaction fits baseline parameters -> clean. Recommended Action: ALLOW.");
        }

        java.util.Map<String, Object> report = new java.util.HashMap<>();
        report.put("investigationSummary", summary);
        report.put("evidenceStrength", strength);
        report.put("recommendedAction", action);
        report.put("customerMessage", message);
        report.put("auditTrail", audit.toString());

        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            return "{\"error\":\"Failed to serialize simulated report\"}";
        }
    }
}
