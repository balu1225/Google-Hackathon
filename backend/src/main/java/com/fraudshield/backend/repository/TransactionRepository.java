package com.fraudshield.backend.repository;

import com.fraudshield.backend.model.Transaction;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends MongoRepository<Transaction, String> {
    List<Transaction> findTop5BySenderAccountOrderByTimestampDesc(String senderAccount);
}
