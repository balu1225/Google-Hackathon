package com.fraudshield.backend.service;

import tools.jackson.databind.ObjectMapper;
import com.fraudshield.backend.model.Transaction;
import com.fraudshield.backend.model.User;
import com.fraudshield.backend.model.FraudCase;
import com.fraudshield.backend.repository.TransactionRepository;
import com.fraudshield.backend.repository.UserRepository;
import com.fraudshield.backend.repository.FraudCaseRepository;
import com.fraudshield.backend.config.LiveStreamWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class IngestionService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FraudCaseRepository fraudCaseRepository;

    @Autowired
    private GeminiService geminiService;

    @Autowired
    private LiveStreamWebSocketHandler webSocketHandler;

    @Autowired
    private ObjectMapper objectMapper;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private boolean isIngesting = false;

    public void startIngestion(String filePath) {
        if (isIngesting) return;
        isIngesting = true;

        executorService.submit(() -> {
            try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
                String line;
                boolean isFirstLine = true;
                while ((line = br.readLine()) != null && isIngesting) {
                    if (isFirstLine) {
                        isFirstLine = false;
                        continue; // skip header
                    }

                    String[] parts = line.split(",");
                    if (parts.length >= 10) {
                        Transaction t = new Transaction();
                        t.setTransactionId(parts[0]);
                        t.setTimestamp(LocalDateTime.parse(parts[1]));
                        t.setSenderAccount(parts[2]);
                        t.setReceiverAccount(parts[3]);
                        t.setAmount(Double.parseDouble(parts[4]));
                        t.setTransactionType(parts[5]);
                        t.setMerchantCategory(parts[6]);
                        t.setLocation(parts[7]);
                        t.setDeviceUsed(parts[8]);
                        t.setIsFraud(Boolean.parseBoolean(parts[9]));

                        // Check user baseline behaviors
                        Optional<User> userOpt = userRepository.findByAccountId(t.getSenderAccount());
                        if (userOpt.isPresent()) {
                            User user = userOpt.get();
                            boolean locationMismatch = !user.getFrequentLocations().contains(t.getLocation());
                            boolean deviceMismatch = !user.getFrequentDevices().contains(t.getDeviceUsed());
                            boolean amountAnomaly = t.getAmount() > (user.getAverageTransactionValue() * 2.0);

                            if (locationMismatch || deviceMismatch || amountAnomaly || t.getIsFraud()) {
                                // Suspected fraud case!
                                t.setIsFraud(true);

                                // Trigger Gemini analysis
                                List<Transaction> history = transactionRepository.findTop5BySenderAccountOrderByTimestampDesc(t.getSenderAccount());
                                GeminiService.GeminiAnalysisResult analysis = geminiService.analyzeTransaction(t, user, history);

                                FraudCase fraudCase = new FraudCase();
                                fraudCase.setTransactionId(t.getTransactionId());
                                fraudCase.setAccountId(t.getSenderAccount());
                                fraudCase.setDetectedAt(LocalDateTime.now());
                                fraudCase.setAiReasoning(analysis.getDetailedReasoning());
                                fraudCase.setRiskScore(analysis.getRiskScore());
                                fraudCase.setStatus("OPEN");

                                fraudCaseRepository.save(fraudCase);

                                // Broadcast fraud alert
                                try {
                                    String alertJson = String.format("{\"type\":\"FRAUD_CASE\",\"data\":%s}", 
                                            objectMapper.writeValueAsString(fraudCase));
                                    webSocketHandler.broadcast(alertJson);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        }

                        transactionRepository.save(t);

                        // Broadcast transaction event
                        try {
                            String txnJson = String.format("{\"type\":\"TRANSACTION\",\"data\":%s}", 
                                    objectMapper.writeValueAsString(t));
                            webSocketHandler.broadcast(txnJson);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }

                        // Simulate real-time delay (e.g. 1000ms per transaction for better visual speed on UI)
                        Thread.sleep(1000);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isIngesting = false;
                // Broadcast that ingestion has stopped/finished
                webSocketHandler.broadcast("{\"type\":\"SYSTEM\",\"message\":\"Ingestion stopped\"}");
            }
        });
    }

    public void stopIngestion() {
        isIngesting = false;
    }
}
