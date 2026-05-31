package com.fraudshield.backend.repository;

import com.fraudshield.backend.model.Transaction;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends MongoRepository<Transaction, String> {
    List<Transaction> findTop5BySenderAccountOrderByTimestampDesc(String senderAccount);
    List<Transaction> findTop10BySenderAccountOrderByTimestampDesc(String senderAccount);
    List<Transaction> findTop5BySenderAccountAndIsFraudNotOrderByTimestampDesc(String senderAccount, Boolean isFraud);
    List<Transaction> findTop10BySenderAccountAndIsFraudNotOrderByTimestampDesc(String senderAccount, Boolean isFraud);
    Optional<Transaction> findByTransactionId(String transactionId);
    List<Transaction> findTop10ByReceiverAccountOrderByTimestampDesc(String receiverAccount);
    List<Transaction> findTop500ByOrderByTimestampDesc();
}
