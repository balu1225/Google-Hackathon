package com.fraudshield.backend.repository;

import com.fraudshield.backend.model.FraudCase;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FraudCaseRepository extends MongoRepository<FraudCase, String> {
    Optional<FraudCase> findByTransactionId(String transactionId);
    List<FraudCase> findByStatus(String status);
    List<FraudCase> findTop500ByOrderByDetectedAtDesc();
}
