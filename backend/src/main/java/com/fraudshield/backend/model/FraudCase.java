package com.fraudshield.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "fraud_cases")
public class FraudCase {

    @Id
    private String id;
    
    private String transactionId;
    private String accountId;
    private LocalDateTime detectedAt;
    
    private String aiReasoning; // The explanation from Gemini
    private Double riskScore;
    private String status; // e.g., "OPEN", "CLOSED", "ACCOUNT_FROZEN"
    
    private String customerExplanation;
    private String regulatoryAuditRecord;
    private String investigationReport;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public LocalDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(LocalDateTime detectedAt) { this.detectedAt = detectedAt; }

    public String getAiReasoning() { return aiReasoning; }
    public void setAiReasoning(String aiReasoning) { this.aiReasoning = aiReasoning; }

    public Double getRiskScore() { return riskScore; }
    public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCustomerExplanation() { return customerExplanation; }
    public void setCustomerExplanation(String customerExplanation) { this.customerExplanation = customerExplanation; }

    public String getRegulatoryAuditRecord() { return regulatoryAuditRecord; }
    public void setRegulatoryAuditRecord(String regulatoryAuditRecord) { this.regulatoryAuditRecord = regulatoryAuditRecord; }

    public String getInvestigationReport() { return investigationReport; }
    public void setInvestigationReport(String investigationReport) { this.investigationReport = investigationReport; }
}
