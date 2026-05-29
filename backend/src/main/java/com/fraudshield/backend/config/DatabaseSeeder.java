package com.fraudshield.backend.config;

import com.fraudshield.backend.model.User;
import com.fraudshield.backend.repository.UserRepository;
import com.fraudshield.backend.repository.TransactionRepository;
import com.fraudshield.backend.repository.FraudCaseRepository;
import com.fraudshield.backend.service.IngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

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

    @Override
    public void run(String... args) throws Exception {
        // Clear all collections to ensure clean demo state
        userRepository.deleteAll();
        transactionRepository.deleteAll();
        fraudCaseRepository.deleteAll();

        // Seed Alice
        User alice = new User();
        alice.setAccountId("ACC101");
        alice.setName("Alice Smith");
        alice.setFrequentLocations(Arrays.asList("New York", "Boston"));
        alice.setFrequentDevices(Arrays.asList("mobile"));
        alice.setAverageTransactionValue(50.0);
        userRepository.save(alice);

        // Seed Bob
        User bob = new User();
        bob.setAccountId("ACC102");
        bob.setName("Bob Johnson");
        bob.setFrequentLocations(Arrays.asList("London"));
        bob.setFrequentDevices(Arrays.asList("web"));
        bob.setAverageTransactionValue(120.0);
        userRepository.save(bob);

        // Seed Charlie
        User charlie = new User();
        charlie.setAccountId("ACC103");
        charlie.setName("Charlie Brown");
        charlie.setFrequentLocations(Arrays.asList("San Francisco"));
        charlie.setFrequentDevices(Arrays.asList("mobile", "web"));
        charlie.setAverageTransactionValue(80.0);
        userRepository.save(charlie);

        System.out.println("Database successfully seeded with Alice, Bob, and Charlie profiles.");

        // Bulk load all sample transactions from CSV on startup
        ingestionService.bulkLoad("sample_transactions.csv");
        System.out.println("Successfully seeded database with all baseline transactions!");
    }
}
