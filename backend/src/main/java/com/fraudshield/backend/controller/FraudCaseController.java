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
        return ResponseEntity.ok(fraudCaseRepository.findAll());
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<FraudCase> getCaseById(@PathVariable String caseId) {
        Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
        return caseOpt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/cases/{caseId}/status")
    public ResponseEntity<FraudCase> updateCaseStatus(@PathVariable String caseId, @RequestParam String status) {
        Optional<FraudCase> caseOpt = fraudCaseRepository.findById(caseId);
        if (caseOpt.isPresent()) {
            FraudCase fc = caseOpt.get();
            fc.setStatus(status);
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

    @GetMapping("/users/{accountId}")
    public ResponseEntity<User> getUserByAccountId(@PathVariable String accountId) {
        Optional<User> userOpt = userRepository.findByAccountId(accountId);
        return userOpt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<Transaction>> getAllTransactions() {
        return ResponseEntity.ok(transactionRepository.findAll());
    }
}
