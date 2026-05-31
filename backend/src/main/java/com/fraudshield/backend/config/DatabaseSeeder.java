package com.fraudshield.backend.config;

import com.fraudshield.backend.repository.UserRepository;
import com.fraudshield.backend.repository.TransactionRepository;
import com.fraudshield.backend.repository.FraudCaseRepository;
import com.fraudshield.backend.service.IngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private FraudCaseRepository fraudCaseRepository;

    @Autowired
    private IngestionService ingestionService;

    @Value("${fraudshield.dataset.path:sample_transactions.csv}")
    private String datasetPath;

    @Override
    public void run(String... args) throws Exception {
        ingestionService.initializeDatabase(datasetPath);
        System.out.println("DatabaseSeeder execution finished.");
    }
}
