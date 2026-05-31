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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class IngestionService implements DisposableBean {

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

    @Autowired
    private MongoTemplate mongoTemplate;

    @Value("${fraudshield.dataset.path:sample_transactions.csv}")
    private String datasetPath;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ExecutorService geminiExecutor = Executors.newFixedThreadPool(10);
    private final ConcurrentHashMap<String, User> userCache = new ConcurrentHashMap<>();
    private final List<Transaction> streamableTransactions = new java.util.ArrayList<>();
    private boolean isIngesting = false;

    private User getCachedUser(String accountId) {
        if (accountId == null) return null;
        User u = userCache.get(accountId);
        if (u == null) {
            Optional<User> uOpt = userRepository.findByAccountId(accountId);
            if (uOpt.isPresent()) {
                u = uOpt.get();
                userCache.put(accountId, u);
            }
        }
        return u;
    }

    public void evictUserFromCache(String accountId) {
        if (accountId != null) {
            userCache.remove(accountId);
        }
    }

    private boolean validateTransaction(Transaction t) {
        if (t == null) return false;
        if (t.getTransactionId() == null || t.getTransactionId().trim().isEmpty()) return false;
        if (t.getSenderAccount() == null || t.getSenderAccount().trim().isEmpty()) return false;
        if (t.getReceiverAccount() == null || t.getReceiverAccount().trim().isEmpty()) return false;
        if (t.getAmount() == null || t.getAmount() <= 0) return false;
        if (t.getTimestamp() == null) return false;
        return true;
    }

    private BufferedReader getBufferedReader(String filePath) throws Exception {
        File file = new File(filePath);
        if (file.exists()) {
            return new BufferedReader(new FileReader(file));
        }

        // Try resource stream from ClassLoader
        InputStream is = getClass().getClassLoader().getResourceAsStream(filePath);
        if (is == null) {
            // Strip folder prefix if it's there
            String filename = filePath;
            if (filePath.contains("/")) {
                filename = filePath.substring(filePath.lastIndexOf("/") + 1);
            }
            is = getClass().getClassLoader().getResourceAsStream(filename);
        }

        if (is == null) {
            // Try prefixing with backend/
            File fileWithBackend = new File("backend/" + filePath);
            if (fileWithBackend.exists()) {
                return new BufferedReader(new FileReader(fileWithBackend));
            }
            throw new java.io.FileNotFoundException("Could not resolve file path: " + filePath);
        }

        return new BufferedReader(new InputStreamReader(is));
    }

    public void initializeDatabase(String filePath) {
        long txCount = transactionRepository.count();
        long uCount = userRepository.count();
        System.out.println(String.format("Debug: MongoDB counts - Transactions: %d, Users: %d", txCount, uCount));
        // Force fresh database seeding to recalculate baselines from historical data
        /*
        if (txCount > 0 && uCount > 0) {
            System.out.println("Database is already seeded with transactions and user profiles. Loading streamable transaction pool...");
            loadStreamableTransactionsOnly(filePath);
            return;
        }
        */

        System.out.println("Initializing dynamic 95/5 database seed from: " + filePath);
        
        transactionRepository.deleteAll();
        userRepository.deleteAll();
        fraudCaseRepository.deleteAll();
        userCache.clear();

        List<Transaction> allTransactions = new java.util.ArrayList<>();
        try (BufferedReader br = getBufferedReader(filePath)) {
            String line;
            boolean isFirstLine = true;
            int parsedCount = 0;
            int maxLimit = filePath.contains("sample_transactions.csv") ? 100 : 20000;
            
            while ((line = br.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }
                Transaction t = parseLine(line);
                if (t != null) {
                    allTransactions.add(t);
                    parsedCount++;
                    if (parsedCount >= maxLimit) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading dataset for initialization: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        if (allTransactions.isEmpty()) {
            System.err.println("No transactions loaded during database initialization.");
            return;
        }

        // Sort by timestamp for timeseries order
        allTransactions.sort((t1, t2) -> {
            if (t1.getTimestamp() == null || t2.getTimestamp() == null) return 0;
            return t1.getTimestamp().compareTo(t2.getTimestamp());
        });

        int total = allTransactions.size();
        int historicalCount = (int) (total * 0.95);
        int recentCount = total - historicalCount;

        System.out.println(String.format("Total transactions loaded: %d. Historical (95%%): %d, Recent (5%%): %d", total, historicalCount, recentCount));

        List<Transaction> historicalTxns = allTransactions.subList(0, historicalCount);
        List<Transaction> recentTxns = allTransactions.subList(historicalCount, total);

        if (!historicalTxns.isEmpty()) {
            mongoTemplate.insert(historicalTxns, Transaction.class);
            System.out.println("Seeded " + historicalTxns.size() + " historical transactions into database.");
        }

        // Compute dynamic user baseline profiles
        java.util.Map<String, List<Transaction>> txnsBySender = new java.util.HashMap<>();
        for (Transaction t : historicalTxns) {
            if (t.getSenderAccount() != null) {
                txnsBySender.computeIfAbsent(t.getSenderAccount(), k -> new java.util.ArrayList<>()).add(t);
            }
        }

        List<User> usersToInsert = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, List<Transaction>> entry : txnsBySender.entrySet()) {
            String accountId = entry.getKey();
            List<Transaction> userTxns = entry.getValue();

            User u = new User();
            u.setAccountId(accountId);
            u.setName("User " + accountId);

            double sum = 0;
            for (Transaction t : userTxns) {
                sum += t.getAmount();
            }
            u.setAverageTransactionValue(sum / userTxns.size());

            java.util.Set<String> locations = new java.util.HashSet<>();
            java.util.Set<String> devices = new java.util.HashSet<>();
            for (Transaction t : userTxns) {
                if (t.getLocation() != null) locations.add(t.getLocation());
                if (t.getDeviceUsed() != null) devices.add(t.getDeviceUsed());
            }
            u.setFrequentLocations(new java.util.ArrayList<>(locations));
            u.setFrequentDevices(new java.util.ArrayList<>(devices));

            usersToInsert.add(u);
        }

        if (!usersToInsert.isEmpty()) {
            mongoTemplate.insert(usersToInsert, User.class);
            System.out.println("Dynamically seeded " + usersToInsert.size() + " user baseline profiles.");
        }

        this.streamableTransactions.clear();
        this.streamableTransactions.addAll(recentTxns);
        injectSuspiciousTransactions();
        System.out.println("Loaded " + streamableTransactions.size() + " recent transactions into streamable pool.");
    }

    private void loadStreamableTransactionsOnly(String filePath) {
        List<Transaction> allTransactions = new java.util.ArrayList<>();
        try (BufferedReader br = getBufferedReader(filePath)) {
            String line;
            boolean isFirstLine = true;
            int parsedCount = 0;
            int maxLimit = filePath.contains("sample_transactions.csv") ? 100 : 20000;
            while ((line = br.readLine()) != null) {
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }
                Transaction t = parseLine(line);
                if (t != null) {
                    allTransactions.add(t);
                    parsedCount++;
                    if (parsedCount >= maxLimit) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading dataset: " + e.getMessage());
            return;
        }

        allTransactions.sort((t1, t2) -> {
            if (t1.getTimestamp() == null || t2.getTimestamp() == null) return 0;
            return t1.getTimestamp().compareTo(t2.getTimestamp());
        });

        int total = allTransactions.size();
        int historicalCount = (int) (total * 0.95);
        
        this.streamableTransactions.clear();
        if (historicalCount < total) {
            this.streamableTransactions.addAll(allTransactions.subList(historicalCount, total));
        }
        injectSuspiciousTransactions();
        System.out.println("Reloaded " + streamableTransactions.size() + " streamable transactions into pool.");
    }

    public void startIngestion(String filePath) {
        if (isIngesting) return;
        isIngesting = true;
        final String resolvedPath = (filePath == null || filePath.trim().isEmpty()) ? datasetPath : filePath;

        executorService.submit(() -> {
            try {
                if (streamableTransactions.isEmpty()) {
                    loadStreamableTransactionsOnly(resolvedPath);
                }

                System.out.println("Starting stream ingestion of " + streamableTransactions.size() + " recent transactions...");

                for (Transaction t : streamableTransactions) {
                    if (!isIngesting) break;

                    processTransaction(t);
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isIngesting = false;
                webSocketHandler.broadcast("{\"type\":\"SYSTEM\",\"message\":\"Ingestion stopped\"}");
            }
        });
    }

    private Transaction parseLine(String line) {
        String[] parts = line.split(",", -1);
        if (parts.length >= 10) {
            try {
                Transaction t = new Transaction();
                t.setTransactionId(parts[0].trim());
                t.setTimestamp(LocalDateTime.parse(parts[1].trim()));
                t.setSenderAccount(parts[2].trim());
                t.setReceiverAccount(parts[3].trim());
                t.setAmount(Double.parseDouble(parts[4].trim()));
                t.setTransactionType(parts[5].trim());
                t.setMerchantCategory(parts[6].trim());
                t.setLocation(parts[7].trim());
                t.setDeviceUsed(parts[8].trim());
                
                String isFraudStr = parts[9].trim();
                t.setIsFraud(isFraudStr.equalsIgnoreCase("true") || isFraudStr.equalsIgnoreCase("1"));
                
                if (parts.length >= 18) {
                    t.setFraudType(parts[10].trim().isEmpty() ? null : parts[10].trim());
                    
                    String timeStr = parts[11].trim();
                    t.setTimeSinceLastTransaction(timeStr.isEmpty() ? null : Double.parseDouble(timeStr));
                    
                    String spendingDevStr = parts[12].trim();
                    t.setSpendingDeviationScore(spendingDevStr.isEmpty() ? null : Double.parseDouble(spendingDevStr));
                    
                    String velocityStr = parts[13].trim();
                    t.setVelocityScore(velocityStr.isEmpty() ? null : Double.parseDouble(velocityStr));
                    
                    String geoStr = parts[14].trim();
                    t.setGeoAnomalyScore(geoStr.isEmpty() ? null : Double.parseDouble(geoStr));
                    
                    t.setPaymentChannel(parts[15].trim().isEmpty() ? null : parts[15].trim());
                    t.setIpAddress(parts[16].trim().isEmpty() ? null : parts[16].trim());
                    t.setDeviceHash(parts[17].trim().isEmpty() ? null : parts[17].trim());
                }
                
                return t;
            } catch (Exception e) {
                // Ignore parsing errors for individual corrupt lines
            }
        }
        return null;
    }

    private void processTransaction(Transaction t) {
        long startTime = System.currentTimeMillis();

        if (!validateTransaction(t)) {
            System.err.println("Skipping malformed transaction: " + t.getTransactionId());
            return;
        }

        // Check if transaction was already processed/ingested to prevent duplicates
        Optional<Transaction> existingTxOpt = transactionRepository.findByTransactionId(t.getTransactionId());
        if (existingTxOpt.isPresent()) {
            // Set MongoDB ID to overwrite it and return early to avoid duplicate processing/Gemini calls
            t.setId(existingTxOpt.get().getId());
            transactionRepository.save(t);
            return;
        }

        // Check user baseline behaviors using cached user lookup
        User user = getCachedUser(t.getSenderAccount());
        if (user != null) {
            boolean locationMismatch = !user.getFrequentLocations().contains(t.getLocation());
            boolean deviceMismatch = !user.getFrequentDevices().contains(t.getDeviceUsed());
            boolean amountAnomaly = t.getAmount() > (user.getAverageTransactionValue() * 2.0);

            if (locationMismatch || deviceMismatch || amountAnomaly || Boolean.TRUE.equals(t.getIsFraud())) {
                // Suspected fraud case!
                t.setIsFraud(true);

                // Instantly create a pending FraudCase record in MongoDB
                final FraudCase fraudCase = new FraudCase();
                fraudCase.setTransactionId(t.getTransactionId());
                fraudCase.setAccountId(t.getSenderAccount());
                fraudCase.setDetectedAt(LocalDateTime.now());
                fraudCase.setAiReasoning("Analyzing transaction context with Gemini AI...");
                fraudCase.setRiskScore(0.5); // temporary initial score
                fraudCase.setStatus("OPEN");
                fraudCase.setCustomerExplanation("Analyzing transaction security context...");
                fraudCase.setRegulatoryAuditRecord("{\"status\":\"PENDING_ANALYSIS\"}");

                fraudCaseRepository.save(fraudCase);

                // Broadcast initial fraud alert instantly
                try {
                    String alertJson = String.format("{\"type\":\"FRAUD_CASE\",\"data\":%s}", 
                            objectMapper.writeValueAsString(fraudCase));
                    webSocketHandler.broadcast(alertJson);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                // Asynchronously submit Gemini analysis to run in the background
                final User finalUser = user;
                geminiExecutor.submit(() -> {
                    try {
                        // Fetch history (top 5 clean transactions) - this happens asynchronously to save inline latency
                        List<Transaction> history = transactionRepository.findTop5BySenderAccountAndIsFraudNotOrderByTimestampDesc(t.getSenderAccount(), true);
                        GeminiService.GeminiAnalysisResult analysis = geminiService.analyzeTransaction(t, finalUser, history);

                        // Fetch and update the saved FraudCase
                        Optional<FraudCase> caseToUpdateOpt = fraudCaseRepository.findByTransactionId(t.getTransactionId());
                        if (caseToUpdateOpt.isPresent()) {
                            FraudCase fc = caseToUpdateOpt.get();
                            fc.setAiReasoning(analysis.getDetailedReasoning());
                            fc.setRiskScore(analysis.getRiskScore());
                            fc.setCustomerExplanation(analysis.getCustomerExplanation());
                            fc.setRegulatoryAuditRecord(analysis.getRegulatoryAuditRecord());

                            if (analysis.getRiskScore() > 0.7) {
                                try {
                                    System.out.println("Risk score > 0.7 (" + analysis.getRiskScore() + "). Running autonomous investigation agent for transaction " + t.getTransactionId() + "...");
                                    List<Transaction> receiverHistory = transactionRepository.findTop10ByReceiverAccountOrderByTimestampDesc(t.getReceiverAccount());
                                    String reportJson = geminiService.runAgentInvestigation(finalUser, t, history, receiverHistory);
                                    fc.setInvestigationReport(reportJson);
                                    System.out.println("Autonomous investigation report completed for transaction " + t.getTransactionId());
                                } catch (Exception agentEx) {
                                    System.err.println("Error running autonomous agent investigation for case: " + fc.getId());
                                    agentEx.printStackTrace();
                                }
                            }

                            fraudCaseRepository.save(fc);

                            // Broadcast case update to the client
                            String updateJson = String.format("{\"type\":\"CASE_UPDATE\",\"data\":%s}", 
                                    objectMapper.writeValueAsString(fc));
                            webSocketHandler.broadcast(updateJson);
                        }
                    } catch (Exception ex) {
                        System.err.println("Error in async Gemini analysis for transaction " + t.getTransactionId());
                        ex.printStackTrace();
                    }
                });
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

        if (!Boolean.TRUE.equals(t.getIsFraud())) {
            triggerAsyncProfileUpdate(t.getSenderAccount());
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println(String.format("Ingestion processed transaction %s in %d ms", t.getTransactionId(), duration));
    }

    public void triggerAsyncProfileUpdate(String senderAccount) {
        if (senderAccount == null) return;
        geminiExecutor.submit(() -> {
            try {
                User u = userRepository.findByAccountId(senderAccount).orElse(null);
                if (u != null) {
                    List<Transaction> history = transactionRepository.findTop10BySenderAccountAndIsFraudNotOrderByTimestampDesc(senderAccount, true);
                    GeminiService.ProfileUpdateResult update = geminiService.updateUserProfile(u, history);
                    if (update != null && update.isShouldUpdate()) {
                        List<String> locs = u.getFrequentLocations();
                        if (locs == null) {
                            locs = new java.util.ArrayList<>();
                        } else {
                            locs = new java.util.ArrayList<>(locs);
                        }
                        if (update.getAddLocations() != null) {
                            for (String loc : update.getAddLocations()) {
                                if (!locs.contains(loc)) {
                                    locs.add(loc);
                                }
                            }
                        }
                        if (update.getRemoveLocations() != null) {
                            for (String loc : update.getRemoveLocations()) {
                                locs.remove(loc);
                            }
                        }
                        u.setFrequentLocations(locs);

                        List<String> devs = u.getFrequentDevices();
                        if (devs == null) {
                            devs = new java.util.ArrayList<>();
                        } else {
                            devs = new java.util.ArrayList<>(devs);
                        }
                        if (update.getAddDevices() != null) {
                            for (String dev : update.getAddDevices()) {
                                if (!devs.contains(dev)) {
                                    devs.add(dev);
                                }
                            }
                        }
                        if (update.getRemoveDevices() != null) {
                            for (String dev : update.getRemoveDevices()) {
                                devs.remove(dev);
                            }
                        }
                        u.setFrequentDevices(devs);

                        if (update.getUpdatedAverageValue() != null && update.getUpdatedAverageValue() > 0) {
                            u.setAverageTransactionValue(update.getUpdatedAverageValue());
                        }

                        userRepository.save(u);
                        evictUserFromCache(senderAccount);
                        System.out.println("Auto-updated behavioral baseline for user " + senderAccount + ": " + update.detailedReasoning);
                    }
                }
            } catch (Exception ex) {
                System.err.println("Error updating profile for user " + senderAccount);
                ex.printStackTrace();
            }
        });
    }

    public void stopIngestion() {
        isIngesting = false;
    }

    private void injectSuspiciousTransactions() {
        if (streamableTransactions.isEmpty()) return;

        List<User> users = userRepository.findAll();
        if (users.isEmpty()) {
            System.out.println("No users found to inject suspicious transactions.");
            return;
        }

        System.out.println("Injecting anomalous/fraudulent test cases into the streaming pool...");
        int total = streamableTransactions.size();

        // Case 1: Direct Raw Fraud Flag (Index 2)
        if (total > 2) {
            Transaction t = streamableTransactions.get(2);
            User u = users.get(0);
            t.setSenderAccount(u.getAccountId());
            t.setIsFraud(true);
            t.setFraudType("Identity Theft");
            t.setAmount(u.getAverageTransactionValue() * 0.5);
            System.out.println("Injected Raw Fraud Flag on Transaction " + t.getTransactionId() + " for Account " + u.getAccountId());
        }

        // Case 2: Location Mismatch Anomaly (Index 8)
        if (total > 8) {
            Transaction t = streamableTransactions.get(8);
            User u = users.size() > 1 ? users.get(1) : users.get(0);
            t.setSenderAccount(u.getAccountId());
            t.setLocation("Unknown Hacker Territory");
            t.setIsFraud(false);
            System.out.println("Injected Location Mismatch on Transaction " + t.getTransactionId() + " for Account " + u.getAccountId());
        }

        // Case 3: Device Mismatch Anomaly (Index 14)
        if (total > 14) {
            Transaction t = streamableTransactions.get(14);
            User u = users.size() > 2 ? users.get(2) : users.get(0);
            t.setSenderAccount(u.getAccountId());
            t.setDeviceUsed("anomalous_linux_terminal");
            t.setIsFraud(false);
            System.out.println("Injected Device Mismatch on Transaction " + t.getTransactionId() + " for Account " + u.getAccountId());
        }

        // Case 4: Massive Amount/Spending Deviation Anomaly (Index 20)
        if (total > 20) {
            Transaction t = streamableTransactions.get(20);
            User u = users.size() > 3 ? users.get(3) : users.get(0);
            t.setSenderAccount(u.getAccountId());
            t.setAmount(u.getAverageTransactionValue() * 4.5);
            t.setIsFraud(false);
            System.out.println("Injected Amount Anomaly on Transaction " + t.getTransactionId() + " for Account " + u.getAccountId());
        }
    }

    @Override
    public void destroy() throws Exception {
        executorService.shutdown();
        geminiExecutor.shutdown();
    }
}
