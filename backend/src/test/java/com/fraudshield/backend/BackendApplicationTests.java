package com.fraudshield.backend;

import com.fraudshield.backend.model.Transaction;
import com.fraudshield.backend.model.User;
import com.fraudshield.backend.model.FraudCase;
import com.fraudshield.backend.repository.TransactionRepository;
import com.fraudshield.backend.repository.UserRepository;
import com.fraudshield.backend.repository.FraudCaseRepository;
import com.fraudshield.backend.service.IngestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BackendApplicationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private FraudCaseRepository fraudCaseRepository;

    @Autowired
    private IngestionService ingestionService;

    private static final String PROFILE_ACCOUNT = "ACC_PROFILE_TEST";
    private static final String AGENT_ACCOUNT = "ACC_AGENT_TEST";

    @AfterEach
    void cleanUp() {
        // Clean profile test data
        userRepository.findByAccountId(PROFILE_ACCOUNT).ifPresent(userRepository::delete);
        List<Transaction> txsProfile = transactionRepository.findTop10BySenderAccountOrderByTimestampDesc(PROFILE_ACCOUNT);
        transactionRepository.deleteAll(txsProfile);
        ingestionService.evictUserFromCache(PROFILE_ACCOUNT);

        // Clean agent test data
        userRepository.findByAccountId(AGENT_ACCOUNT).ifPresent(userRepository::delete);
        List<Transaction> txsAgent = transactionRepository.findTop10BySenderAccountOrderByTimestampDesc(AGENT_ACCOUNT);
        transactionRepository.deleteAll(txsAgent);
        
        // Clean agent test receiver history
        List<Transaction> recTxs = transactionRepository.findTop10ByReceiverAccountOrderByTimestampDesc("ACC_MULE_REC");
        transactionRepository.deleteAll(recTxs);
        
        fraudCaseRepository.findByTransactionId("TX_AGENT_TEST").ifPresent(fraudCaseRepository::delete);
        ingestionService.evictUserFromCache(AGENT_ACCOUNT);
    }

    @Test
    void testLivingBehavioralProfileUpdate() throws Exception {
        // 1. Seed a test User baseline
        User user = new User();
        user.setAccountId(PROFILE_ACCOUNT);
        user.setName("Integ Test User");
        user.setFrequentLocations(new ArrayList<>(List.of("Home")));
        user.setFrequentDevices(new ArrayList<>(List.of("Phone")));
        user.setAverageTransactionValue(100.0);
        userRepository.save(user);

        // 2. Create and save 3 clean transactions using a new location "Paris" and device "Tablet"
        for (int i = 1; i <= 3; i++) {
            Transaction tx = new Transaction();
            tx.setTransactionId("TX_INTEG_" + i);
            tx.setSenderAccount(PROFILE_ACCOUNT);
            tx.setReceiverAccount("ACC_REC");
            tx.setAmount(110.0);
            tx.setLocation("Paris");
            tx.setDeviceUsed("Tablet");
            tx.setTimestamp(LocalDateTime.now().minusMinutes(i));
            tx.setIsFraud(false);
            transactionRepository.save(tx);
        }

        // 3. Trigger baseline update asynchronously
        ingestionService.triggerAsyncProfileUpdate(PROFILE_ACCOUNT);

        // 4. Poll/Wait for up to 15 seconds for the baseline update to write to MongoDB
        User updatedUser = null;
        for (int i = 0; i < 150; i++) {
            Thread.sleep(100);
            Optional<User> opt = userRepository.findByAccountId(PROFILE_ACCOUNT);
            if (opt.isPresent()) {
                User temp = opt.get();
                if (temp.getFrequentLocations().contains("Paris")) {
                    updatedUser = temp;
                    break;
                }
            }
        }

        // 5. Verify expectations
        assertNotNull(updatedUser, "User profile should have been updated");
        assertTrue(updatedUser.getFrequentLocations().contains("Paris"), "Frequent locations should include Paris");
        assertTrue(updatedUser.getFrequentDevices().contains("Tablet"), "Frequent devices should include Tablet");
        assertEquals(110.0, updatedUser.getAverageTransactionValue(), 0.01, "Average transaction value should update to 110.0");

        // 6. Process a 4th transaction from "Paris" and "Tablet".
        Transaction tx4 = new Transaction();
        tx4.setTransactionId("TX_INTEG_4");
        tx4.setSenderAccount(PROFILE_ACCOUNT);
        tx4.setReceiverAccount("ACC_REC");
        tx4.setAmount(105.0);
        tx4.setLocation("Paris");
        tx4.setDeviceUsed("Tablet");
        tx4.setTimestamp(LocalDateTime.now());
        tx4.setIsFraud(false);

        ingestionService.triggerAsyncProfileUpdate(PROFILE_ACCOUNT);
        Thread.sleep(200);
        ingestionService.evictUserFromCache(PROFILE_ACCOUNT);

        java.lang.reflect.Method method = IngestionService.class.getDeclaredMethod("processTransaction", Transaction.class);
        method.setAccessible(true);
        method.invoke(ingestionService, tx4);

        assertFalse(Boolean.TRUE.equals(tx4.getIsFraud()), "Transaction 4 should NOT be flagged as fraud");
    }

    @Test
    void testAutonomousInvestigationAgent() throws Exception {
        // 1. Seed a test User baseline
        User user = new User();
        user.setAccountId(AGENT_ACCOUNT);
        user.setName("Agent Test User");
        user.setFrequentLocations(new ArrayList<>(List.of("Home")));
        user.setFrequentDevices(new ArrayList<>(List.of("Phone")));
        user.setAverageTransactionValue(100.0);
        userRepository.save(user);

        // 2. Create a high-risk transaction (triggers location mismatch + device mismatch + amount mismatch, resulting in > 0.7 risk score)
        Transaction tx = new Transaction();
        tx.setTransactionId("TX_AGENT_TEST");
        tx.setSenderAccount(AGENT_ACCOUNT);
        tx.setReceiverAccount("ACC_MULE_REC");
        tx.setAmount(250.0);
        tx.setLocation("Unknown Hacker Territory"); // location mismatch
        tx.setDeviceUsed("anomalous_linux_terminal"); // device mismatch
        tx.setTimestamp(LocalDateTime.now());
        tx.setIsFraud(false);

        // We also want to seed receiver incoming history to test money mule detection (distinct senders >= 3)
        for (int i = 1; i <= 3; i++) {
            Transaction recTx = new Transaction();
            recTx.setTransactionId("TX_MULE_" + i);
            recTx.setSenderAccount("ACC_MULE_SENDER_" + i); // 3 different senders
            recTx.setReceiverAccount("ACC_MULE_REC");
            recTx.setAmount(500.0);
            recTx.setLocation("Online");
            recTx.setTimestamp(LocalDateTime.now().minusHours(i));
            transactionRepository.save(recTx);
        }

        // Before running, evict cache
        ingestionService.evictUserFromCache(AGENT_ACCOUNT);

        Optional<User> uInDb = userRepository.findByAccountId(AGENT_ACCOUNT);
        System.out.println("DEBUG: User in DB: " + uInDb.isPresent());
        if (uInDb.isPresent()) {
            System.out.println("DEBUG: User frequent locations: " + uInDb.get().getFrequentLocations());
        }

        // Run processTransaction via reflection
        java.lang.reflect.Method method = IngestionService.class.getDeclaredMethod("processTransaction", Transaction.class);
        method.setAccessible(true);
        method.invoke(ingestionService, tx);

        // 3. Poll/Wait for up to 30 seconds for the async agent investigation to run and save the report
        FraudCase fc = null;
        for (int i = 0; i < 300; i++) {
            Thread.sleep(100);
            Optional<FraudCase> opt = fraudCaseRepository.findByTransactionId("TX_AGENT_TEST");
            if (opt.isPresent()) {
                FraudCase temp = opt.get();
                System.out.println("DEBUG: Poll iteration " + i + " - Fraud Case in DB. Report: " + temp.getInvestigationReport() + " | Risk Score: " + temp.getRiskScore());
                if (temp.getInvestigationReport() != null) {
                    fc = temp;
                    break;
                }
            } else {
                System.out.println("DEBUG: Poll iteration " + i + " - Fraud Case NOT in DB");
            }
        }

        // 4. Verify expectations
        assertNotNull(fc, "FraudCase should have been created and updated with an investigation report");
        String reportJson = fc.getInvestigationReport();
        assertNotNull(reportJson, "Investigation report JSON should not be null");

        // Parse and assert JSON structure fields
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.JsonNode report = mapper.readTree(reportJson);
        assertTrue(report.has("investigationSummary"), "Report should contain investigationSummary");
        assertTrue(report.has("evidenceStrength"), "Report should contain evidenceStrength");
        assertTrue(report.has("recommendedAction"), "Report should contain recommendedAction");
        assertTrue(report.has("customerMessage"), "Report should contain customerMessage");
        assertTrue(report.has("auditTrail"), "Report should contain auditTrail");

        // Since receiver history had 3 distinct senders, the money mule check should have triggered a HIGH evidence strength FREEZE_ACCOUNT
        assertEquals("HIGH", report.path("evidenceStrength").asText());
        assertEquals("FREEZE_ACCOUNT", report.path("recommendedAction").asText());
        assertTrue(report.path("investigationSummary").asText().contains("money mule"), "Summary should flag money mule behavior");
    }
}
