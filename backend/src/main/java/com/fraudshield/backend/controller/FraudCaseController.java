package com.fraudshield.backend.controller;

import tools.jackson.databind.ObjectMapper;
import com.fraudshield.backend.model.FraudCase;
import com.fraudshield.backend.model.User;
import com.fraudshield.backend.model.Transaction;
import com.fraudshield.backend.repository.FraudCaseRepository;
import com.fraudshield.backend.repository.UserRepository;
import com.fraudshield.backend.repository.TransactionRepository;
import com.fraudshield.backend.config.LiveStreamWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class FraudCaseController {

    @Autowired
    private FraudCaseRepository fraudCaseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LiveStreamWebSocketHandler webSocketHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/cases")
    public ResponseEntity<List<FraudCase>> getAllCases() {
        return ResponseEntity.ok(fraudCaseRepository.findTop500ByOrderByDetectedAtDesc());
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<FraudCase> getCaseById(@PathVariable String caseId) {
        Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
        return caseOpt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Autowired
    private com.fraudshield.backend.service.IngestionService ingestionService;

    @PutMapping("/cases/{caseId}/status")
    public ResponseEntity<FraudCase> updateCaseStatus(@PathVariable String caseId, @RequestParam String status) {
        Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
        if (caseOpt.isPresent()) {
            FraudCase fc = caseOpt.get();
            fc.setStatus(status);
            fraudCaseRepository.save(fc);

            if ("CLOSED".equals(status)) {
                Optional<Transaction> txOpt = transactionRepository.findByTransactionId(fc.getTransactionId());
                if (txOpt.isPresent()) {
                    Transaction tx = txOpt.get();
                    tx.setIsFraud(false);
                    transactionRepository.save(tx);
                    ingestionService.triggerAsyncProfileUpdate(tx.getSenderAccount());
                }
            }

            // Broadcast case update
            try {
                String eventJson = String.format("{\"type\":\"CASE_UPDATE\",\"data\":%s}",
                        objectMapper.writeValueAsString(fc));
                webSocketHandler.broadcast(eventJson);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            return ResponseEntity.ok(fc);
        }
        return ResponseEntity.notFound().build();
    }

    @PutMapping("/cases/{caseId}/report")
    public ResponseEntity<FraudCase> updateCaseReport(@PathVariable String caseId, @RequestBody String report) {
        Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
        if (caseOpt.isPresent()) {
            FraudCase fc = caseOpt.get();
            fc.setInvestigationReport(report);
            fraudCaseRepository.save(fc);

            // Broadcast case update
            try {
                String eventJson = String.format("{\"type\":\"CASE_UPDATE\",\"data\":%s}",
                        objectMapper.writeValueAsString(fc));
                webSocketHandler.broadcast(eventJson);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            return ResponseEntity.ok(fc);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/users")
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userRepository.findAll());
    }

    @GetMapping("/users/{accountId}")
    public ResponseEntity<User> getUserByAccountId(@PathVariable String accountId) {
        Optional<User> userOpt = userRepository.findByAccountId(accountId);
        return userOpt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/transactions/count")
    public ResponseEntity<Long> getTransactionCount() {
        return ResponseEntity.ok(transactionRepository.count());
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<Transaction>> getAllTransactions() {
        return ResponseEntity.ok(transactionRepository.findTop500ByOrderByTimestampDesc());
    }

    @DeleteMapping("/debug/clear")
    public ResponseEntity<String> clearData() {
        transactionRepository.deleteAll();
        fraudCaseRepository.deleteAll();
        return ResponseEntity.ok("Cleared all transactions and cases successfully.");
    }
}
