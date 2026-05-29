import React, { useState, useEffect, useRef } from 'react';
import './App.css'; // Let's keep empty or import as default, the main styles are in index.css

interface Transaction {
  id?: string;
  transactionId: string;
  timestamp: string;
  senderAccount: string;
  receiverAccount: string;
  amount: number;
  transactionType: string;
  merchantCategory: string;
  location: string;
  deviceUsed: string;
  isFraud: boolean;
}

interface FraudCase {
  id: string;
  transactionId: string;
  accountId: string;
  detectedAt: string;
  aiReasoning: string;
  riskScore: number;
  status: string;
}

interface User {
  id?: string;
  accountId: string;
  name: string;
  frequentLocations: string[];
  frequentDevices: string[];
  averageTransactionValue: number;
}

const API_BASE = "http://localhost:8080/api";
const WS_URL = "ws://localhost:8080/ws/stream";

function App() {
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [cases, setCases] = useState<FraudCase[]>([]);
  const [selectedCase, setSelectedCase] = useState<FraudCase | null>(null);
  const [selectedUser, setSelectedUser] = useState<User | null>(null);
  const [selectedTxn, setSelectedTxn] = useState<Transaction | null>(null);

  const [isIngesting, setIsIngesting] = useState(false);
  const [filePath, setFilePath] = useState("src/main/resources/sample_transactions.csv");
  const [wsStatus, setWsStatus] = useState<"connecting" | "connected" | "disconnected">("connecting");
  const wsRef = useRef<WebSocket | null>(null);

  // Fetch initial data
  useEffect(() => {
    fetch(`${API_BASE}/cases`)
      .then(res => res.json())
      .then(data => setCases(data.reverse())) // Show newest first
      .catch(err => console.error("Error fetching cases:", err));

    fetch(`${API_BASE}/transactions`)
      .then(res => res.json())
      .then(data => setTransactions(data.reverse())) // Show newest first
      .catch(err => console.error("Error fetching transactions:", err));
  }, []);

  // WebSocket Connection
  useEffect(() => {
    connectWS();
    return () => {
      if (wsRef.current) wsRef.current.close();
    };
  }, []);

  const connectWS = () => {
    setWsStatus("connecting");
    const ws = new WebSocket(WS_URL);
    wsRef.current = ws;

    ws.onopen = () => {
      setWsStatus("connected");
      console.log("WebSocket connected");
    };

    ws.onclose = () => {
      setWsStatus("disconnected");
      console.log("WebSocket disconnected");
    };

    ws.onerror = (err) => {
      console.error("WebSocket error:", err);
      setWsStatus("disconnected");
    };

    ws.onmessage = (event) => {
      try {
        const payload = JSON.parse(event.data);
        if (payload.type === "TRANSACTION") {
          const newTxn: Transaction = payload.data;
          setTransactions(prev => [newTxn, ...prev]);
        } else if (payload.type === "FRAUD_CASE") {
          const newCase: FraudCase = payload.data;
          setCases(prev => [newCase, ...prev]);
        } else if (payload.type === "CASE_UPDATE") {
          const updatedCase: FraudCase = payload.data;
          setCases(prev => prev.map(c => c.id === updatedCase.id ? updatedCase : c));
          setSelectedCase(prev => {
            if (prev && prev.id === updatedCase.id) {
              return updatedCase;
            }
            return prev;
          });
        } else if (payload.type === "SYSTEM") {
          if (payload.message === "Ingestion stopped") {
            setIsIngesting(false);
          }
        }
      } catch (e) {
        // Handle raw string messages
        if (event.data.includes("Connected")) {
          setWsStatus("connected");
        }
      }
    };
  };

  // Ingestion Controls
  const startIngestion = () => {
    setIsIngesting(true);
    fetch(`${API_BASE}/ingest/start?filePath=${encodeURIComponent(filePath)}`, {
      method: "POST"
    })
      .then(res => {
        if (!res.ok) throw new Error("Failed to start ingestion");
      })
      .catch(err => {
        console.error(err);
        setIsIngesting(false);
      });
  };

  const stopIngestion = () => {
    fetch(`${API_BASE}/ingest/stop`, {
      method: "POST"
    })
      .then(res => {
        if (res.ok) setIsIngesting(false);
      })
      .catch(err => console.error(err));
  };

  // Case Selection
  const selectCase = (c: FraudCase) => {
    setSelectedCase(c);
    setSelectedUser(null);
    setSelectedTxn(null);

    // Fetch user details
    fetch(`${API_BASE}/users/${c.accountId}`)
      .then(res => {
        if (res.ok) return res.json();
        throw new Error("User not found");
      })
      .then(data => setSelectedUser(data))
      .catch(err => console.error(err));

    // Find transaction details in local list or fetch
    const localTxn = transactions.find(t => t.transactionId === c.transactionId);
    if (localTxn) {
      setSelectedTxn(localTxn);
    } else {
      // Fallback: search database if needed (can be fetched from API if needed)
      fetch(`${API_BASE}/transactions`)
        .then(res => res.json())
        .then((txns: Transaction[]) => {
          const found = txns.find(t => t.transactionId === c.transactionId);
          if (found) setSelectedTxn(found);
        })
        .catch(err => console.error(err));
    }
  };

  // Update Case Status
  const updateCaseStatus = (caseId: string, status: string) => {
    fetch(`${API_BASE}/cases/${caseId}/status?status=${status}`, {
      method: "PUT"
    })
      .then(res => {
        if (res.ok) return res.json();
        throw new Error("Failed to update status");
      })
      .then((updated: FraudCase) => {
        setCases(prev => prev.map(c => c.id === caseId ? updated : c));
        setSelectedCase(updated);
      })
      .catch(err => console.error(err));
  };

  // Anomaly checks for UI highlighting
  const isLocationMismatch = selectedTxn && selectedUser && !selectedUser.frequentLocations.includes(selectedTxn.location);
  const isDeviceMismatch = selectedTxn && selectedUser && !selectedUser.frequentDevices.includes(selectedTxn.deviceUsed);
  const isAmountAnomaly = selectedTxn && selectedUser && selectedTxn.amount > (selectedUser.averageTransactionValue * 2);

  // Stats
  const openCasesCount = cases.filter(c => c.status === 'OPEN').length;

  return (
    <>
      <header>
        <div className="logo-container">
          <div className="logo-icon">🛡️</div>
          <div>
            <div className="logo-text">FRAUDSHIELD AI</div>
            <div style={{ fontSize: '0.65rem', color: 'var(--text-secondary)' }}>AGENTIC REAL-TIME COGNITION</div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '1.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <span style={{ 
              width: '8px', 
              height: '8px', 
              borderRadius: '50%', 
              backgroundColor: wsStatus === 'connected' ? 'var(--color-success)' : wsStatus === 'connecting' ? 'var(--color-warning)' : 'var(--color-danger)',
              boxShadow: wsStatus === 'connected' ? '0 0 8px var(--color-success)' : 'none'
            }} />
            <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>
              Socket: {wsStatus}
            </span>
            {wsStatus === 'disconnected' && (
              <button onClick={connectWS} className="btn btn-secondary" style={{ padding: '0.2rem 0.5rem', fontSize: '0.65rem' }}>Reconnect</button>
            )}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <input 
              type="text" 
              value={filePath} 
              onChange={(e) => setFilePath(e.target.value)} 
              placeholder="CSV file path..." 
              style={{
                background: 'rgba(255,255,255,0.05)',
                border: '1px solid var(--border-color)',
                borderRadius: '4px',
                padding: '0.4rem 0.75rem',
                color: 'white',
                fontSize: '0.8rem',
                width: '260px'
              }}
              disabled={isIngesting}
            />
            {isIngesting ? (
              <button onClick={stopIngestion} className="btn btn-danger">
                🛑 Stop Stream
              </button>
            ) : (
              <button onClick={startIngestion} className="btn btn-primary">
                ⚡ Start Stream
              </button>
            )}
          </div>
        </div>
      </header>

      <main className="dashboard-grid">
        {/* Left Column: Transaction Feed */}
        <section className="column">
          <div className="column-header">
            <h2 className="column-title">Live Transactions</h2>
            <div className="stats-strip">
              <span className="stat-bubble">{transactions.length} processed</span>
            </div>
          </div>
          <div className="column-content">
            {transactions.length === 0 ? (
              <div style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '2rem', fontSize: '0.85rem' }}>
                Waiting for transaction stream to start...
              </div>
            ) : (
              transactions.map((t) => (
                <div 
                  key={t.transactionId} 
                  className={`stream-item ${t.isFraud ? 'fraud' : ''} ${selectedTxn?.transactionId === t.transactionId ? 'active' : ''}`}
                  onClick={() => {
                    // If this transaction is fraud, select the case
                    const matchingCase = cases.find(c => c.transactionId === t.transactionId);
                    if (matchingCase) {
                      selectCase(matchingCase);
                    } else {
                      // Show transaction details only
                      setSelectedTxn(t);
                      setSelectedCase(null);
                      setSelectedUser(null);
                      // Fetch user anyway
                      fetch(`${API_BASE}/users/${t.senderAccount}`)
                        .then(res => res.json())
                        .then(data => setSelectedUser(data))
                        .catch(() => setSelectedUser(null));
                    }
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.25rem' }}>
                    <span style={{ fontWeight: 600, fontSize: '0.85rem' }}>{t.transactionId}</span>
                    <span style={{ 
                      fontWeight: 700, 
                      fontSize: '0.85rem',
                      color: t.isFraud ? 'var(--color-danger)' : 'var(--color-success)'
                    }}>${t.amount.toFixed(2)}</span>
                  </div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', display: 'flex', justifyContent: 'space-between' }}>
                    <span>Acc: {t.senderAccount}</span>
                    <span style={{ textTransform: 'capitalize' }}>{t.location} ({t.deviceUsed})</span>
                  </div>
                  {t.isFraud && (
                    <div style={{ 
                      marginTop: '0.5rem', 
                      fontSize: '0.65rem', 
                      background: 'rgba(239,68,68,0.15)', 
                      color: '#fca5a5', 
                      padding: '0.2rem 0.4rem', 
                      borderRadius: '4px',
                      fontWeight: 600,
                      display: 'inline-block'
                    }}>
                      ⚠️ AI ANOMALY DETECTED
                    </div>
                  )}
                </div>
              ))
            )}
          </div>
        </section>

        {/* Middle Column: Active Cases */}
        <section className="column">
          <div className="column-header">
            <h2 className="column-title">Alert Queue</h2>
            <div className="stats-strip">
              <span className="stat-bubble danger">{openCasesCount} active</span>
            </div>
          </div>
          <div className="column-content">
            {cases.length === 0 ? (
              <div style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '2rem', fontSize: '0.85rem' }}>
                No active threats detected.
              </div>
            ) : (
              cases.map((c) => (
                <div 
                  key={c.id} 
                  className={`alert-card ${selectedCase?.id === c.id ? 'active' : ''}`}
                  onClick={() => selectCase(c)}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.4rem' }}>
                    <span style={{ fontWeight: 600, fontSize: '0.85rem' }}>Case: {c.transactionId}</span>
                    <span className={`badge badge-${c.status.toLowerCase()}`}>{c.status}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.75rem' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Account: {c.accountId}</span>
                    <span style={{ 
                      fontWeight: 700, 
                      color: c.riskScore > 0.7 ? 'var(--color-danger)' : c.riskScore > 0.4 ? 'var(--color-warning)' : 'var(--color-success)'
                    }}>Score: {(c.riskScore * 100).toFixed(0)}%</span>
                  </div>
                </div>
              ))
            )}
          </div>
        </section>

        {/* Right Column: Case Details / Investigations */}
        <section className="column" style={{ background: 'rgba(10,11,16,0.3)' }}>
          <div className="column-header">
            <h2 className="column-title">Investigation Board</h2>
          </div>
          <div className="column-content">
            {!selectedTxn ? (
              <div className="details-placeholder">
                <span className="details-placeholder-icon">🔎</span>
                <p style={{ fontWeight: 500 }}>Select a transaction or alert to initiate investigation</p>
                <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>Analyze signals, check baseline deviation, and review Gemini AI cognitive explanation.</p>
              </div>
            ) : (
              <div>
                {/* Transaction Identity */}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1.25rem' }}>
                  <div>
                    <h3 style={{ fontSize: '1.1rem', fontWeight: 700 }}>{selectedTxn.transactionId}</h3>
                    <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Type: {selectedTxn.transactionType} | Category: {selectedTxn.merchantCategory}</p>
                  </div>
                  {selectedCase && (
                    <span className={`badge badge-${selectedCase.status.toLowerCase()}`}>{selectedCase.status}</span>
                  )}
                </div>

                {/* Audit side-by-side comparison */}
                <div className="section-title">Baseline Comparison</div>
                {selectedUser ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                    <div style={{ fontSize: '0.8rem', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.5rem' }}>
                      <strong>Cardholder:</strong> {selectedUser.name} (Acc: {selectedUser.accountId})
                    </div>
                    
                    <div className="comparison-grid">
                      <div className={`compare-box ${isLocationMismatch ? 'mismatch' : ''}`}>
                        <div className="compare-title">Transaction Location</div>
                        <div className="compare-val">{selectedTxn.location}</div>
                        <div style={{ fontSize: '0.7rem', color: 'var(--text-secondary)', marginTop: '0.2rem' }}>
                          Baseline: {selectedUser.frequentLocations.join(", ")}
                        </div>
                      </div>

                      <div className={`compare-box ${isDeviceMismatch ? 'mismatch' : ''}`}>
                        <div className="compare-title">Device Used</div>
                        <div className="compare-val">{selectedTxn.deviceUsed}</div>
                        <div style={{ fontSize: '0.7rem', color: 'var(--text-secondary)', marginTop: '0.2rem' }}>
                          Baseline: {selectedUser.frequentDevices.join(", ")}
                        </div>
                      </div>

                      <div className={`compare-box ${isAmountAnomaly ? 'mismatch' : ''}`}>
                        <div className="compare-title">Amount</div>
                        <div className="compare-val">${selectedTxn.amount.toFixed(2)}</div>
                        <div style={{ fontSize: '0.7rem', color: 'var(--text-secondary)', marginTop: '0.2rem' }}>
                          Baseline Avg: ${selectedUser.averageTransactionValue.toFixed(2)} (Limit: ${(selectedUser.averageTransactionValue * 2).toFixed(2)})
                        </div>
                      </div>
                    </div>
                  </div>
                ) : (
                  <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', background: 'rgba(255,255,255,0.02)', padding: '0.75rem', borderRadius: '6px', border: '1px solid var(--border-color)' }}>
                    Loading sender behavioral profile...
                  </div>
                )}

                {/* Gemini AI Reasoning */}
                {selectedCase ? (
                  <div className="ai-panel">
                    <div className="ai-header">
                      <span>✨</span> Gemini Cognitive Reasoning
                    </div>
                    
                    <div className="score-row">
                      <span className="score-lbl">Risk Score Assessment</span>
                      <span className="score-num" style={{
                        color: selectedCase.riskScore > 0.7 ? 'var(--color-danger)' : selectedCase.riskScore > 0.4 ? 'var(--color-warning)' : 'var(--color-success)'
                      }}>{(selectedCase.riskScore * 100).toFixed(0)}%</span>
                    </div>

                    <div className="score-bar-bg">
                      <div className="score-bar-fg" style={{
                        width: `${selectedCase.riskScore * 100}%`,
                        backgroundColor: selectedCase.riskScore > 0.7 ? 'var(--color-danger)' : selectedCase.riskScore > 0.4 ? 'var(--color-warning)' : 'var(--color-success)',
                        boxShadow: `0 0 8px ${selectedCase.riskScore > 0.7 ? 'var(--color-danger)' : selectedCase.riskScore > 0.4 ? 'var(--color-warning)' : 'var(--color-success)'}`
                      }} />
                    </div>

                    <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                      <strong>AI Verdict:</strong> {selectedCase.riskScore > 0.7 ? 'CRITICAL RISK' : selectedCase.riskScore > 0.4 ? 'MODERATE ABNORMALITY' : 'LOW SUSPICION'}
                    </div>

                    <div className="ai-reasoning">
                      {selectedCase.aiReasoning}
                    </div>
                  </div>
                ) : (
                  selectedTxn.isFraud ? (
                    <div style={{ marginTop: '1rem', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      Loading Gemini Cognitive Reasoning...
                    </div>
                  ) : (
                    <div style={{ 
                      marginTop: '1rem', 
                      background: 'rgba(16, 185, 129, 0.05)', 
                      border: '1px solid rgba(16, 185, 129, 0.2)', 
                      padding: '1rem', 
                      borderRadius: '8px',
                      color: '#a7f3d0'
                    }}>
                      <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.85rem', marginBottom: '0.25rem' }}>
                        <span>✅</span> Approved Transaction
                      </div>
                      <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                        This transaction perfectly aligns with the cardholder's baseline behavioral profiles. No anomaly flagged.
                      </p>
                    </div>
                  )
                )}

                {/* Actions Footer */}
                {selectedCase && selectedCase.status === "OPEN" && (
                  <div className="actions-footer">
                    <button 
                      className="btn btn-secondary" 
                      onClick={() => updateCaseStatus(selectedCase.id, "CLOSED")}
                    >
                      Dismiss Case
                    </button>
                    <button 
                      className="btn btn-primary" 
                      style={{ background: 'linear-gradient(135deg, var(--color-danger), #b91c1c)', boxShadow: '0 4px 12px rgba(239, 68, 68, 0.2)' }}
                      onClick={() => updateCaseStatus(selectedCase.id, "ACCOUNT_FROZEN")}
                    >
                      ❄️ Freeze Account
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        </section>
      </main>
    </>
  );
}

export default App;
