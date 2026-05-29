package com.fraudshield.backend.service;

import com.fraudshield.backend.model.Transaction;
import com.fraudshield.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class IngestionService {

    @Autowired
    private TransactionRepository transactionRepository;

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
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

                        transactionRepository.save(t);

                        // Simulate real-time delay (e.g. 100ms per transaction)
                        // This allows the UI to show live streaming
                        Thread.sleep(100);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isIngesting = false;
            }
        });
    }

    public void stopIngestion() {
        isIngesting = false;
    }
}
