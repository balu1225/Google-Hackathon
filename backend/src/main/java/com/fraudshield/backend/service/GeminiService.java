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

@Service
public class GeminiService {

    @Value("${gcp.project.id:}")
    private String projectId;

    @Value("${gcp.region:us-central1}")
    private String region;

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
    }

    public GeminiAnalysisResult analyzeTransaction(Transaction txn, User user, List<Transaction> recentHistory) {
        // Fallback check if Project ID is empty/not configured
        if (projectId == null || projectId.trim().isEmpty()) {
            System.out.println("Warning: gcp.project.id is not configured. Running fallback simulation analysis.");
            return runSimulationAnalysis(txn, user, recentHistory);
        }

        try {
            // Retrieve OAuth access token from GCP credentials
            String accessToken;
            try {
                accessToken = getVertexAccessToken();
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
                "CRITICAL: Evaluate geographic velocity (e.g. is it physically possible to travel between the locations of the recent transactions and this new transaction in the elapsed time?).",
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

            responseSchema.put("properties", properties);
            responseSchema.put("required", new String[]{"riskScore", "confidenceLevel", "primarySignals", "detailedReasoning", "recommendedAction"});

            generationConfig.put("responseSchema", responseSchema);
            requestBody.put("generationConfig", generationConfig);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            // Construct Vertex AI endpoint URL
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
                return objectMapper.readValue(jsonText, GeminiAnalysisResult.class);
            } else {
                System.err.println("Vertex AI Gemini call failed with status: " + response.statusCode() + " response: " + response.body());
                return runSimulationAnalysis(txn, user, recentHistory);
            }

        } catch (Exception e) {
            System.err.println("Exception calling Vertex AI: " + e.getMessage());
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

        return result;
    }
}
