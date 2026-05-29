package com.fraudshield.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fraudshield.backend.model.Transaction;
import com.fraudshield.backend.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class GeminiService {

    @Value("${gemini.api.key:}")
    private String apiKey;

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

    public GeminiAnalysisResult analyzeTransaction(Transaction txn, User user) {
        // Fallback check if API key is empty/not configured
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.out.println("Warning: GEMINI_API_KEY is not configured. Running fallback simulation analysis.");
            return runSimulationAnalysis(txn, user);
        }

        try {
            String prompt = String.format(
                "You are an expert fraud detection AI. Analyze this financial transaction against the user's historical baseline profile and output if it is fraud, risk details, and recommended action.\n\n" +
                "User Name: %s\n" +
                "Account ID: %s\n" +
                "User Baseline:\n" +
                "  - Frequent Locations: %s\n" +
                "  - Frequent Devices: %s\n" +
                "  - Average Transaction Value: $%.2f\n\n" +
                "Transaction under investigation:\n" +
                "  - Transaction ID: %s\n" +
                "  - Timestamp: %s\n" +
                "  - Amount: $%.2f\n" +
                "  - Type: %s\n" +
                "  - Location: %s\n" +
                "  - Device Used: %s\n" +
                "  - Merchant Category: %s\n" +
                "  - Receiver Account: %s\n",
                user.getName(), user.getAccountId(),
                String.join(", ", user.getFrequentLocations()),
                String.join(", ", user.getFrequentDevices()),
                user.getAverageTransactionValue(),
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
            content.put("parts", new Object[]{part});
            requestBody.put("contents", new Object[]{content});

            // generationConfig
            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("responseMimeType", "application/json");

            // responseSchema
            Map<String, Object> responseSchema = new HashMap<>();
            responseSchema.put("type", "OBJECT");
            
            Map<String, Object> properties = new HashMap<>();
            properties.put("riskScore", Map.of("type", "NUMBER"));
            properties.put("confidenceLevel", Map.of("type", "STRING"));
            properties.put("primarySignals", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")));
            properties.put("detailedReasoning", Map.of("type", "STRING"));
            properties.put("recommendedAction", Map.of("type", "STRING"));

            responseSchema.put("properties", properties);
            responseSchema.put("required", new String[]{"riskScore", "confidenceLevel", "primarySignals", "detailedReasoning", "recommendedAction"});

            generationConfig.put("responseSchema", responseSchema);
            requestBody.put("generationConfig", generationConfig);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
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
                System.err.println("Gemini API call failed with status: " + response.statusCode() + " response: " + response.body());
                return runSimulationAnalysis(txn, user);
            }

        } catch (Exception e) {
            System.err.println("Exception calling Gemini API: " + e.getMessage());
            return runSimulationAnalysis(txn, user);
        }
    }

    private GeminiAnalysisResult runSimulationAnalysis(Transaction txn, User user) {
        GeminiAnalysisResult result = new GeminiAnalysisResult();
        boolean locationMismatch = !user.getFrequentLocations().contains(txn.getLocation());
        boolean deviceMismatch = !user.getFrequentDevices().contains(txn.getDeviceUsed());
        boolean valueAnomaly = txn.getAmount() > (user.getAverageTransactionValue() * 2);

        double score = 0.1;
        var signals = new java.util.ArrayList<String>();
        var reasoning = new StringBuilder("Simulation Anomaly Analyzer flagged this transaction. ");

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
