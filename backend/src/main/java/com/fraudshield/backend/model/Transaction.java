package com.fraudshield.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "transactions")
public class Transaction {

    @Id
    private String id;
    
    private String transactionId;
    private LocalDateTime timestamp;
    private String senderAccount;
    private String receiverAccount;
    private Double amount;
    private String transactionType; // withdrawal/payment/deposit
    private String merchantCategory;
    private String location;
    private String deviceUsed; // mobile/web/atm/pos
    private Boolean isFraud;

    // Advanced Kaggle Dataset fields
    private String fraudType;
    private Double timeSinceLastTransaction;
    private Double spendingDeviationScore;
    private Double velocityScore;
    private Double geoAnomalyScore;
    private String paymentChannel;
    private String ipAddress;
    private String deviceHash;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getSenderAccount() { return senderAccount; }
    public void setSenderAccount(String senderAccount) { this.senderAccount = senderAccount; }

    public String getReceiverAccount() { return receiverAccount; }
    public void setReceiverAccount(String receiverAccount) { this.receiverAccount = receiverAccount; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public String getMerchantCategory() { return merchantCategory; }
    public void setMerchantCategory(String merchantCategory) { this.merchantCategory = merchantCategory; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getDeviceUsed() { return deviceUsed; }
    public void setDeviceUsed(String deviceUsed) { this.deviceUsed = deviceUsed; }

    public Boolean getIsFraud() { return isFraud; }
    public void setIsFraud(Boolean isFraud) { this.isFraud = isFraud; }

    public String getFraudType() { return fraudType; }
    public void setFraudType(String fraudType) { this.fraudType = fraudType; }

    public Double getTimeSinceLastTransaction() { return timeSinceLastTransaction; }
    public void setTimeSinceLastTransaction(Double timeSinceLastTransaction) { this.timeSinceLastTransaction = timeSinceLastTransaction; }

    public Double getSpendingDeviationScore() { return spendingDeviationScore; }
    public void setSpendingDeviationScore(Double spendingDeviationScore) { this.spendingDeviationScore = spendingDeviationScore; }

    public Double getVelocityScore() { return velocityScore; }
    public void setVelocityScore(Double velocityScore) { this.velocityScore = velocityScore; }

    public Double getGeoAnomalyScore() { return geoAnomalyScore; }
    public void setGeoAnomalyScore(Double geoAnomalyScore) { this.geoAnomalyScore = geoAnomalyScore; }

    public String getPaymentChannel() { return paymentChannel; }
    public void setPaymentChannel(String paymentChannel) { this.paymentChannel = paymentChannel; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getDeviceHash() { return deviceHash; }
    public void setDeviceHash(String deviceHash) { this.deviceHash = deviceHash; }
}
