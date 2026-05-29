import React, { useState, useEffect, useRef } from 'react';
import './App.css';

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
  const [usersMap, setUsersMap] = useState<Record<string, User>>({});
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
      .then(data => setCases(data.reverse()))
      .catch(err => console.error("Error fetching cases:", err));

    fetch(`${API_BASE}/transactions`)
      .then(res => res.json())
      .then(data => setTransactions(data.reverse()))
      .catch(err => console.error("Error fetching transactions:", err));

    // Fetch user baselines to map flags on client-side
    fetch(`${API_BASE}/users`)
      .then(res => res.json())
      .then((data: User[]) => {
        const map: Record<string, User> = {};
        data.forEach(u => {
          map[u.accountId] = u;
        });
        setUsersMap(map);
      })
      .catch(err => console.error("Error building users map:", err));
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
    };

    ws.onclose = () => {
      setWsStatus("disconnected");
    };

    ws.onerror = () => {
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
            if (prev && prev.id === updatedCase.id) return updatedCase;
            return prev;
          });
        } else if (payload.type === "SYSTEM") {
          if (payload.message === "Ingestion stopped") {
            setIsIngesting(false);
          }
        }
      } catch (e) {
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
      .catch(() => setIsIngesting(false));
  };

  const stopIngestion = () => {
    fetch(`${API_BASE}/ingest/stop`, { method: "POST" })
      .then(res => { if (res.ok) setIsIngesting(false); });
  };

  // Case Selection
  const selectCase = (c: FraudCase) => {
    setSelectedCase(c);
    setSelectedUser(usersMap[c.accountId] || null);

    const localTxn = transactions.find(t => t.transactionId === c.transactionId);
    if (localTxn) {
      setSelectedTxn(localTxn);
    } else {
      fetch(`${API_BASE}/transactions`)
        .then(res => res.json())
        .then((txns: Transaction[]) => {
          const found = txns.find(t => t.transactionId === c.transactionId);
          if (found) setSelectedTxn(found);
        });
    }
  };

  // Update Case Status
  const updateCaseStatus = (caseId: string, status: string) => {
    fetch(`${API_BASE}/cases/${caseId}/status?status=${status}`, { method: "PUT" })
      .then(res => { if (res.ok) return res.json(); })
      .then((updated: FraudCase) => {
        setCases(prev => prev.map(c => c.id === caseId ? updated : c));
        setSelectedCase(updated);
      });
  };

  // Flag computation helper
  const getFlagDetails = (t: Transaction) => {
    const user = usersMap[t.senderAccount];
    if (!user) return { count: 0, list: [] };

    const list: string[] = [];
    if (!user.frequentLocations.includes(t.location)) list.push("Location");
    if (!user.frequentDevices.includes(t.deviceUsed)) list.push("Device");
    if (t.amount > user.averageTransactionValue * 2) list.push("Value");

    return { count: list.length, list };
  };

  // Highlight details
  const isLocationMismatch = selectedTxn && selectedUser && !selectedUser.frequentLocations.includes(selectedTxn.location);
  const isDeviceMismatch = selectedTxn && selectedUser && !selectedUser.frequentDevices.includes(selectedTxn.deviceUsed);
  const isAmountAnomaly = selectedTxn && selectedUser && selectedTxn.amount > (selectedUser.averageTransactionValue * 2);

  const openCasesCount = cases.filter(c => c.status === 'OPEN').length;

  return (
    <>
      <header>
        <div className="logo-container">
          <div className="logo-icon">🛡️</div>
          <div>
            <div className="logo-text">FraudShield Dashboard</div>
            <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>AI-Assisted Fraud Operations Console</div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '1.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <span style={{ 
              width: '8px', 
              height: '8px', 
              borderRadius: '50%', 
              backgroundColor: wsStatus === 'connected' ? 'var(--color-success)' : 'var(--color-warning)',
            }} />
            <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', textTransform: 'uppercase', fontWeight: 600 }}>
              WebSocket: {wsStatus}
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
                background: 'white',
                border: '1px solid var(--border-color)',
                borderRadius: '4px',
                padding: '0.4rem 0.75rem',
                color: 'var(--text-primary)',
                fontSize: '0.8rem',
                width: '260px'
              }}
              disabled={isIngesting}
            />
            {isIngesting ? (
              <button onClick={stopIngestion} className="btn btn-danger">
                Stop Ingest
              </button>
            ) : (
              <button onClick={startIngestion} className="btn btn-primary">
                Start Ingest
              </button>
            )}
          </div>
        </div>
      </header>

      <main className="dashboard-grid">
        {/* Left Column: Comprehensive Transaction Window */}
        <section className="column" style={{ background: '#f8fafc' }}>
          <div className="column-header">
            <h2 className="column-title">Transactions Window</h2>
            <span className="stat-bubble">{transactions.length} total rows</span>
          </div>
          
          <div className="column-content" style={{ padding: '1rem' }}>
            {transactions.length === 0 ? (
              <div style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '4rem', fontSize: '0.9rem' }}>
                No transaction data. Trigger CSV stream to begin ingestion.
              </div>
            ) : (
              <div className="table-container">
                <table>
                  <thead>
                    <tr>
                      <th>Time</th>
                      <th>Txn ID</th>
                      <th>Account</th>
                      <th>Amount</th>
                      <th>Location</th>
                      <th>Device</th>
                      <th>Category</th>
                      <th>Audit Flags</th>
                    </tr>
                  </thead>
                  <tbody>
                    {transactions.map((t) => {
                      const flags = getFlagDetails(t);
                      const isSelected = selectedTxn?.transactionId === t.transactionId;
                      return (
                        <tr 
                          key={t.transactionId}
                          className={`${t.isFraud ? 'fraud' : ''} ${isSelected ? 'active' : ''}`}
                          onClick={() => {
                            const matchingCase = cases.find(c => c.transactionId === t.transactionId);
                            if (matchingCase) {
                              selectCase(matchingCase);
                            } else {
                              setSelectedTxn(t);
                              setSelectedCase(null);
                              setSelectedUser(usersMap[t.senderAccount] || null);
                            }
                          }}
                        >
                          <td style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                            {t.timestamp.split('T')[1]}
                          </td>
                          <td style={{ fontWeight: 500 }}>{t.transactionId}</td>
                          <td style={{ fontFamily: 'var(--font-mono)' }}>{t.senderAccount}</td>
                          <td style={{ fontWeight: 600, color: t.isFraud ? 'var(--color-danger)' : 'inherit' }}>
                            ${t.amount.toFixed(2)}
                          </td>
                          <td>{t.location}</td>
                          <td style={{ textTransform: 'capitalize' }}>{t.deviceUsed}</td>
                          <td style={{ color: 'var(--text-secondary)', fontSize: '0.8rem' }}>{t.merchantCategory}</td>
                          <td>
                            {flags.count > 0 ? (
                              <span className={`flag-pill ${flags.count >= 3 ? 'alert' : 'warn'}`}>
                                {flags.count} {flags.count === 1 ? 'Flag' : 'Flags'} ({flags.list.join(', ')})
                              </span>
                            ) : (
                              <span className="flag-pill zero">Clear</span>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </section>

        {/* Middle Column: Alert Queue */}
        <section className="column">
          <div className="column-header">
            <h2 className="column-title">Alert Queue</h2>
            <span className="stat-bubble danger">{openCasesCount} Open cases</span>
          </div>
          <div className="column-content">
            {cases.length === 0 ? (
              <div style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '4rem', fontSize: '0.85rem' }}>
                No active threats flagged.
              </div>
            ) : (
              cases.map((c) => (
                <div 
                  key={c.id} 
                  className={`alert-card ${c.status.toLowerCase()}-case ${selectedCase?.id === c.id ? 'active' : ''}`}
                  onClick={() => selectCase(c)}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                    <span style={{ fontWeight: 600, fontSize: '0.85rem' }}>Case: {c.transactionId}</span>
                    <span className={`badge badge-${c.status.toLowerCase()}`}>{c.status}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.8rem' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Account: {c.accountId}</span>
                    <span style={{ 
                      fontWeight: 700, 
                      color: c.riskScore > 0.7 ? 'var(--color-danger)' : c.riskScore > 0.4 ? 'var(--color-warning)' : 'var(--color-success)'
                    }}>Risk: {(c.riskScore * 100).toFixed(0)}%</span>
                  </div>
                </div>
              ))
            )}
          </div>
        </section>

        {/* Right Column: Case Details & Investigation */}
        <section className="column" style={{ background: '#f8fafc' }}>
          <div className="column-header" style={{ borderLeft: '1px solid var(--border-color)' }}>
            <h2 className="column-title">Investigation Board</h2>
          </div>
          <div className="column-content" style={{ borderLeft: '1px solid var(--border-color)' }}>
            {!selectedTxn ? (
              <div className="details-placeholder">
                <span className="details-placeholder-icon">🔎</span>
                <p style={{ fontWeight: 600, color: 'var(--text-primary)', marginBottom: '0.25rem' }}>Select row to audit details</p>
                <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                  View side-by-side transaction metrics against client baseline profiles and review AI explanations.
                </p>
              </div>
            ) : (
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1.25rem' }}>
                  <div>
                    <h3 style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--text-primary)' }}>{selectedTxn.transactionId}</h3>
                    <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Type: {selectedTxn.transactionType} | Category: {selectedTxn.merchantCategory}</p>
                  </div>
                  {selectedCase && (
                    <span className={`badge badge-${selectedCase.status.toLowerCase()}`}>{selectedCase.status}</span>
                  )}
                </div>

                <div className="section-title">Baseline Verification</div>
                {selectedUser ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                    <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      <strong>Cardholder:</strong> {selectedUser.name} (Acc: {selectedUser.accountId})
                    </div>
                    
                    <div className="comparison-grid">
                      <div className={`compare-box ${isLocationMismatch ? 'mismatch' : ''}`}>
                        <div className="compare-title">Transaction Location</div>
                        <div className="compare-val">{selectedTxn.location}</div>
                        <div style={{ fontSize: '0.7rem', opacity: 0.9, marginTop: '0.25rem' }}>
                          Baseline: {selectedUser.frequentLocations.join(", ")}
                        </div>
                      </div>

                      <div className={`compare-box ${isDeviceMismatch ? 'mismatch' : ''}`}>
                        <div className="compare-title">Device Used</div>
                        <div className="compare-val">{selectedTxn.deviceUsed}</div>
                        <div style={{ fontSize: '0.7rem', opacity: 0.9, marginTop: '0.25rem' }}>
                          Baseline: {selectedUser.frequentDevices.join(", ")}
                        </div>
                      </div>

                      <div className={`compare-box ${isAmountAnomaly ? 'mismatch' : ''}`}>
                        <div className="compare-title">Amount</div>
                        <div className="compare-val">${selectedTxn.amount.toFixed(2)}</div>
                        <div style={{ fontSize: '0.7rem', opacity: 0.9, marginTop: '0.25rem' }}>
                          Baseline Avg: ${selectedUser.averageTransactionValue.toFixed(2)} (Limit: ${(selectedUser.averageTransactionValue * 2).toFixed(2)})
                        </div>
                      </div>
                    </div>
                  </div>
                ) : (
                  <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', background: 'white', padding: '0.75rem', borderRadius: '6px', border: '1px solid var(--border-color)' }}>
                    Fetching sender baseline data...
                  </div>
                )}

                {/* Gemini AI Reasoning */}
                {selectedCase ? (
                  <div className="ai-panel">
                    <div className="ai-header">
                      Gemini Assessment
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
                      }} />
                    </div>

                    <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                      <strong>AI Status:</strong> {selectedCase.riskScore > 0.7 ? 'CRITICAL THREAT' : selectedCase.riskScore > 0.4 ? 'ABNORMAL PATTERN' : 'LOW RISK'}
                    </div>

                    <div className="ai-reasoning">
                      {selectedCase.aiReasoning}
                    </div>
                  </div>
                ) : (
                  selectedTxn.isFraud ? (
                    <div style={{ marginTop: '1rem', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      Analyzing sequence with Gemini...
                    </div>
                  ) : (
                    <div style={{ 
                      marginTop: '1rem', 
                      background: 'var(--color-success-bg)', 
                      border: '1px solid var(--color-success-border)', 
                      padding: '1rem', 
                      borderRadius: '8px',
                      color: 'var(--color-success)'
                    }}>
                      <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.85rem', marginBottom: '0.25rem' }}>
                        Approved Transaction
                      </div>
                      <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                        This transaction perfectly aligns with the cardholder's baseline behavioral profile. No anomalies flagged.
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
                      className="btn btn-danger" 
                      onClick={() => updateCaseStatus(selectedCase.id, "ACCOUNT_FROZEN")}
                    >
                      Freeze Account
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
