package com.fraudshield.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.List;

@Document(collection = "users")
public class User {

    @Id
    private String id;
    
    private String accountId;
    private String name;
    
    // Baseline behavior
    private List<String> frequentLocations;
    private List<String> frequentDevices;
    private Double averageTransactionValue;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getFrequentLocations() { return frequentLocations; }
    public void setFrequentLocations(List<String> frequentLocations) { this.frequentLocations = frequentLocations; }

    public List<String> getFrequentDevices() { return frequentDevices; }
    public void setFrequentDevices(List<String> frequentDevices) { this.frequentDevices = frequentDevices; }

    public Double getAverageTransactionValue() { return averageTransactionValue; }
    public void setAverageTransactionValue(Double averageTransactionValue) { this.averageTransactionValue = averageTransactionValue; }
}
