import { useState, useEffect, useRef } from 'react';
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
  fraudType?: string;
  timeSinceLastTransaction?: number;
  spendingDeviationScore?: number;
  velocityScore?: number;
  geoAnomalyScore?: number;
  paymentChannel?: string;
  ipAddress?: string;
  deviceHash?: string;
}

interface FraudCase {
  id: string;
  transactionId: string;
  accountId: string;
  detectedAt: string;
  aiReasoning: string;
  riskScore: number;
  status: string;
  customerExplanation?: string;
  regulatoryAuditRecord?: string;
  investigationReport?: string;
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

const normalizeRisk = (score: number) => {
  if (score === undefined || score === null) return 0;
  return score > 1.0 ? score / 100 : score;
};

function App() {
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [visibleCount, setVisibleCount] = useState(30);
  const [totalCount, setTotalCount] = useState<number>(0);
  const [cases, setCases] = useState<FraudCase[]>([]);
  const [usersMap, setUsersMap] = useState<Record<string, User>>({});
  const [selectedCase, setSelectedCase] = useState<FraudCase | null>(null);
  const [selectedUser, setSelectedUser] = useState<User | null>(null);
  const [selectedTxn, setSelectedTxn] = useState<Transaction | null>(null);
  const [activeTab, setActiveTab] = useState<'assessment' | 'notice' | 'audit' | 'agent'>('assessment');

  const [isIngesting, setIsIngesting] = useState(false);
  const [wsStatus, setWsStatus] = useState<"connecting" | "connected" | "disconnected">("connecting");
  const wsRef = useRef<WebSocket | null>(null);

  // Fetch initial data
  useEffect(() => {
    fetch(`${API_BASE}/cases`)
      .then(res => res.json())
      .then(data => setCases(data.reverse()))
      .catch(err => console.error("Error fetching cases:", err));

    fetch(`${API_BASE}/transactions/count`)
      .then(res => res.json())
      .then(data => setTotalCount(data))
      .catch(err => console.error("Error fetching transactions count:", err));

    fetch(`${API_BASE}/transactions`)
      .then(res => res.json())
      .then(data => setTransactions(data.reverse().slice(0, 500)))
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

    ws.onclose = (event) => {
      setWsStatus("disconnected");
      if (event.code !== 1000) {
        setTimeout(() => {
          connectWS();
        }, 3000);
      }
    };

    ws.onerror = () => {
      setWsStatus("disconnected");
    };

    ws.onmessage = (event) => {
      try {
        const payload = JSON.parse(event.data);
        if (payload.type === "TRANSACTION") {
          const newTxn: Transaction = payload.data;
          setTransactions(prev => {
            if (prev.some(t => t.transactionId === newTxn.transactionId)) return prev;
            setTotalCount(c => c + 1);
            return [newTxn, ...prev].slice(0, 500);
          });
        } else if (payload.type === "FRAUD_CASE") {
          const newCase: FraudCase = payload.data;
          setCases(prev => {
            if (prev.some(c => c.id === newCase.id || c.transactionId === newCase.transactionId)) return prev;
            return [newCase, ...prev].slice(0, 500);
          });
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
        } else if (payload.type === "BULK_LOAD_COMPLETE") {
          setIsIngesting(false);
          // Re-fetch everything
          fetch(`${API_BASE}/cases`)
            .then(res => res.json())
            .then(data => setCases(data.reverse()));
          fetch(`${API_BASE}/transactions/count`)
            .then(res => res.json())
            .then(data => setTotalCount(data));
          fetch(`${API_BASE}/transactions`)
            .then(res => res.json())
            .then(data => setTransactions(data.reverse().slice(0, 500)));
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
    fetch(`${API_BASE}/ingest/start`, {
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
    setActiveTab('assessment');

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

  // Handle Infinite Scroll
  const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const target = e.currentTarget;
    if (target.scrollHeight - target.scrollTop <= target.clientHeight + 50) {
      setVisibleCount(prev => Math.min(prev + 30, transactions.length));
    }
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
              backgroundColor: wsStatus === 'connected' ? '#2563eb' : '#dc2626',
            }} />
            <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', textTransform: 'uppercase', fontWeight: 600 }}>
              WebSocket: {wsStatus}
            </span>
            {wsStatus === 'disconnected' && (
              <button onClick={connectWS} className="btn btn-secondary" style={{ padding: '0.2rem 0.5rem', fontSize: '0.65rem' }}>Reconnect</button>
            )}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            {isIngesting ? (
              <button onClick={stopIngestion} className="btn btn-danger">
                Stop Ingest
              </button>
            ) : (
              <button onClick={startIngestion} className="btn btn-primary">
                Stream Feed
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
            <span className="stat-bubble">{totalCount} total rows</span>
          </div>
          
          <div className="column-content" style={{ padding: '1rem' }} onScroll={handleScroll}>
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
                        <th>Flags</th>
                      </tr>
                    </thead>
                    <tbody>
                      {transactions.slice(0, visibleCount).map((t) => {
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
                                <span className={`flag-pill ${flags.count >= 2 ? 'alert' : 'warn'}`}>
                                  {flags.count} {flags.count === 1 ? 'Flag' : 'Flags'}
                                </span>
                              ) : (
                                <span className="flag-pill zero">0 Flags</span>
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
                      color: normalizeRisk(c.riskScore) > 0.7 ? 'var(--color-danger)' : normalizeRisk(c.riskScore) > 0.4 ? 'var(--color-warning)' : 'var(--color-primary)'
                    }}>Risk: {(normalizeRisk(c.riskScore) * 100).toFixed(0)}%</span>
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
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                    <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      <strong>Cardholder:</strong> Bulk Loaded Client (Acc: {selectedTxn.senderAccount})
                    </div>
                    <div style={{ 
                      fontSize: '0.8rem', 
                      color: 'var(--text-secondary)', 
                      background: 'white', 
                      padding: '0.75rem', 
                      borderRadius: '6px', 
                      border: '1px solid var(--border-color)',
                      lineHeight: '1.4'
                    }}>
                      <div style={{ fontWeight: 600, color: 'var(--color-primary)', marginBottom: '0.25rem' }}>Baseline Not Established</div>
                      This transaction is from a newly imported bulk account. A historical behavioral baseline has not been compiled for this client yet.
                    </div>
                  </div>
                )}

                <div className="section-title">Advanced Anomaly Telemetry</div>
                <div style={{ 
                  background: 'white', 
                  border: '1px solid var(--border-color)', 
                  borderRadius: '6px', 
                  padding: '0.75rem', 
                  display: 'flex', 
                  flexDirection: 'column', 
                  gap: '0.5rem',
                  fontSize: '0.8rem',
                  marginBottom: '1rem'
                }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>IP Address</span>
                    <span style={{ fontWeight: 500, fontFamily: 'var(--font-mono)' }}>{selectedTxn.ipAddress || 'N/A'}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Device Hash</span>
                    <span style={{ fontWeight: 500, fontFamily: 'var(--font-mono)' }}>{selectedTxn.deviceHash || 'N/A'}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Payment Channel</span>
                    <span style={{ fontWeight: 500, textTransform: 'capitalize' }}>{selectedTxn.paymentChannel || 'N/A'}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Time Since Last Txn</span>
                    <span style={{ fontWeight: 500 }}>{selectedTxn.timeSinceLastTransaction !== undefined && selectedTxn.timeSinceLastTransaction !== null ? `${selectedTxn.timeSinceLastTransaction.toFixed(2)} min` : 'N/A'}</span>
                  </div>

                  {selectedTxn.fraudType && (
                    <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--color-danger)', fontWeight: 600 }}>
                      <span>Fraud Type Flag</span>
                      <span>{selectedTxn.fraudType}</span>
                    </div>
                  )}

                  <div style={{ borderTop: '1px solid var(--border-color)', margin: '0.25rem 0' }} />

                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '0.5rem', marginTop: '0.25rem' }}>
                    <div style={{ textAlign: 'center', background: '#f8fafc', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border-color)' }}>
                      <div style={{ fontSize: '0.65rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Spending Dev</div>
                      <div style={{ fontSize: '0.95rem', fontWeight: 600, color: (selectedTxn.spendingDeviationScore || 0) > 2 ? 'var(--color-danger)' : 'inherit' }}>
                        {selectedTxn.spendingDeviationScore !== undefined && selectedTxn.spendingDeviationScore !== null ? selectedTxn.spendingDeviationScore.toFixed(2) : 'N/A'}
                      </div>
                    </div>
                    <div style={{ textAlign: 'center', background: '#f8fafc', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border-color)' }}>
                      <div style={{ fontSize: '0.65rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Velocity Score</div>
                      <div style={{ fontSize: '0.95rem', fontWeight: 600, color: (selectedTxn.velocityScore || 0) > 10 ? 'var(--color-danger)' : 'inherit' }}>
                        {selectedTxn.velocityScore !== undefined && selectedTxn.velocityScore !== null ? selectedTxn.velocityScore.toFixed(0) : 'N/A'}
                      </div>
                    </div>
                    <div style={{ textAlign: 'center', background: '#f8fafc', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border-color)' }}>
                      <div style={{ fontSize: '0.65rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Geo Anomaly</div>
                      <div style={{ fontSize: '0.95rem', fontWeight: 600, color: (selectedTxn.geoAnomalyScore || 0) > 0.8 ? 'var(--color-danger)' : 'inherit' }}>
                        {selectedTxn.geoAnomalyScore !== undefined && selectedTxn.geoAnomalyScore !== null ? selectedTxn.geoAnomalyScore.toFixed(2) : 'N/A'}
                      </div>
                    </div>
                  </div>
                </div>

                {/* Gemini AI Reasoning & Compliance Tabs */}
                {selectedCase ? (
                  <div className="ai-panel">
                    <div className="tab-container">
                      <button 
                        className={`tab-btn ${activeTab === 'assessment' ? 'active' : ''}`}
                        onClick={() => setActiveTab('assessment')}
                      >
                        AI Assessment
                      </button>
                      <button 
                        className={`tab-btn ${activeTab === 'agent' ? 'active' : ''}`}
                        onClick={() => setActiveTab('agent')}
                      >
                        Agent Report
                      </button>
                      <button 
                        className={`tab-btn ${activeTab === 'notice' ? 'active' : ''}`}
                        onClick={() => setActiveTab('notice')}
                      >
                        Customer Notice
                      </button>
                      <button 
                        className={`tab-btn ${activeTab === 'audit' ? 'active' : ''}`}
                        onClick={() => setActiveTab('audit')}
                      >
                        Compliance Audit
                      </button>
                    </div>

                    {activeTab === 'assessment' && (
                      <>
                        <div className="score-row">
                          <span className="score-lbl">Risk Score Assessment</span>
                          <span className="score-num" style={{
                            color: normalizeRisk(selectedCase.riskScore) > 0.7 ? 'var(--color-danger)' : normalizeRisk(selectedCase.riskScore) > 0.4 ? 'var(--color-warning)' : 'var(--color-primary)'
                          }}>{(normalizeRisk(selectedCase.riskScore) * 100).toFixed(0)}%</span>
                        </div>

                        <div className="score-bar-bg">
                          <div className="score-bar-fg" style={{
                            width: `${normalizeRisk(selectedCase.riskScore) * 100}%`,
                            backgroundColor: normalizeRisk(selectedCase.riskScore) > 0.7 ? 'var(--color-danger)' : normalizeRisk(selectedCase.riskScore) > 0.4 ? 'var(--color-warning)' : 'var(--color-primary)',
                          }} />
                        </div>

                        <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginBottom: '0.75rem' }}>
                          <strong>AI Status:</strong> {normalizeRisk(selectedCase.riskScore) > 0.7 ? 'CRITICAL THREAT' : normalizeRisk(selectedCase.riskScore) > 0.4 ? 'ABNORMAL PATTERN' : 'LOW RISK'}
                        </div>

                        <div className="ai-reasoning" style={{ marginTop: '0.5rem' }}>
                          {selectedCase.aiReasoning}
                        </div>
                      </>
                    )}

                    {activeTab === 'notice' && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                        <div style={{ 
                          fontSize: '0.65rem', 
                          color: '#0f766e', 
                          background: '#f0fdf4', 
                          border: '1px solid #bbf7d0', 
                          padding: '0.25rem 0.5rem', 
                          borderRadius: '4px',
                          fontWeight: 600,
                          textTransform: 'uppercase',
                          letterSpacing: '0.5px',
                          width: 'fit-content'
                        }}>
                          SMS / Push Alert Preview (US-REG-E Compliant)
                        </div>
                        <div style={{
                          background: '#f8fafc',
                          border: '1px dashed #cbd5e1',
                          borderRadius: '8px',
                          padding: '1rem',
                          fontFamily: 'system-ui, -apple-system, sans-serif',
                          fontSize: '0.85rem',
                          lineHeight: '1.4',
                          color: '#334155'
                        }}>
                          <strong>Message Content:</strong>
                          <p style={{ marginTop: '0.5rem', fontStyle: 'italic' }}>
                            "{selectedCase.customerExplanation || 'Generating customer safety notice...'}"
                          </p>
                        </div>
                        <div style={{ fontSize: '0.7rem', color: 'var(--text-secondary)' }}>
                          * Direct communication drafted automatically by Gemini AI. Safe to dispatch immediately to customer device.
                        </div>
                      </div>
                    )}

                    {activeTab === 'audit' && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                        <div style={{ 
                          fontSize: '0.65rem', 
                          color: '#b45309', 
                          background: '#fffbeb', 
                          border: '1px solid #fef3c7', 
                          padding: '0.25rem 0.5rem', 
                          borderRadius: '4px',
                          fontWeight: 600,
                          textTransform: 'uppercase',
                          letterSpacing: '0.5px',
                          width: 'fit-content'
                        }}>
                          Regulatory Audit Trail (JSON schema)
                        </div>
                        <div style={{
                          background: '#0f172a',
                          color: '#38bdf8',
                          fontFamily: 'var(--font-mono)',
                          fontSize: '0.75rem',
                          padding: '1rem',
                          borderRadius: '8px',
                          overflowX: 'auto',
                          maxHeight: '240px',
                          overflowY: 'auto',
                          lineHeight: '1.4'
                        }}>
                          <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                            {(() => {
                              try {
                                if (!selectedCase.regulatoryAuditRecord) return "Generating compliance audit logs...";
                                const obj = JSON.parse(selectedCase.regulatoryAuditRecord);
                                return JSON.stringify(obj, null, 2);
                              } catch (e) {
                                return selectedCase.regulatoryAuditRecord;
                              }
                            })()}
                          </pre>
                        </div>
                        <div style={{ fontSize: '0.7rem', color: 'var(--text-secondary)' }}>
                          * Generated automatically for GDPR Article 22 & RBI compliance. Decision logic is audit-logged and archived.
                        </div>
                      </div>
                    )}

                    {activeTab === 'agent' && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                        <div style={{ 
                          fontSize: '0.65rem', 
                          color: '#0f766e', 
                          background: '#f0fdf4', 
                          border: '1px solid #bbf7d0', 
                          padding: '0.25rem 0.5rem', 
                          borderRadius: '4px',
                          fontWeight: 600,
                          textTransform: 'uppercase',
                          letterSpacing: '0.5px',
                          width: 'fit-content'
                        }}>
                          Autonomous Investigation Report
                        </div>

                        {selectedCase.investigationReport ? (() => {
                          try {
                            const report = JSON.parse(selectedCase.investigationReport);
                            const strengthColor = report.evidenceStrength === 'HIGH' ? 'var(--color-danger)' : report.evidenceStrength === 'MEDIUM' ? 'var(--color-warning)' : 'var(--color-primary)';
                            return (
                              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                                <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                                  <span style={{ 
                                    fontSize: '0.75rem', 
                                    fontWeight: 600, 
                                    padding: '0.25rem 0.5rem', 
                                    borderRadius: '4px',
                                    backgroundColor: strengthColor,
                                    color: 'white'
                                  }}>
                                    Evidence: {report.evidenceStrength}
                                  </span>
                                  <span style={{ 
                                    fontSize: '0.75rem', 
                                    fontWeight: 600, 
                                    padding: '0.25rem 0.5rem', 
                                    borderRadius: '4px',
                                    border: '1px solid var(--border-color)',
                                    color: 'var(--text-main)',
                                    background: 'white'
                                  }}>
                                    Action: {report.recommendedAction}
                                  </span>
                                </div>

                                <div style={{ background: 'white', padding: '0.75rem', borderRadius: '6px', border: '1px solid var(--border-color)' }}>
                                  <strong style={{ fontSize: '0.8rem', color: 'var(--color-primary)' }}>Case Summary:</strong>
                                  <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.75rem', color: 'var(--text-main)', lineHeight: '1.4' }}>
                                    {report.investigationSummary}
                                  </p>
                                </div>

                                <div style={{ background: 'white', padding: '0.75rem', borderRadius: '6px', border: '1px solid var(--border-color)' }}>
                                  <strong style={{ fontSize: '0.8rem', color: 'var(--color-primary)' }}>Audit Trail Log:</strong>
                                  <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.75rem', color: 'var(--text-main)', lineHeight: '1.4', fontStyle: 'italic' }}>
                                    {report.auditTrail}
                                  </p>
                                </div>

                                <div style={{ background: '#f8fafc', padding: '0.75rem', borderRadius: '6px', border: '1px dashed var(--border-color)' }}>
                                  <strong style={{ fontSize: '0.8rem', color: '#0f766e' }}>SMS Alert Notification:</strong>
                                  <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.75rem', color: '#334155', lineHeight: '1.4' }}>
                                    "{report.customerMessage}"
                                  </p>
                                </div>
                              </div>
                            );
                          } catch (e) {
                            return (
                              <div style={{ fontSize: '0.75rem', color: 'var(--color-danger)' }}>
                                Failed to parse investigation report: {selectedCase.investigationReport}
                              </div>
                            );
                          }
                        })() : (
                          <div style={{
                            background: '#f8fafc',
                            border: '1px dashed #cbd5e1',
                            borderRadius: '8px',
                            padding: '1rem',
                            fontSize: '0.8rem',
                            color: '#64748b',
                            textAlign: 'center'
                          }}>
                            {normalizeRisk(selectedCase.riskScore) > 0.7 ? (
                              <span>Autonomous agent investigation report is generating in the background...</span>
                            ) : (
                              <span>Autonomous agent investigation report is not required for low-risk alerts (risk &le; 70%).</span>
                            )}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                ) : (
                  selectedTxn.isFraud ? (
                    <div style={{ marginTop: '1rem', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      Analyzing sequence with Gemini...
                    </div>
                  ) : (
                    <div style={{ 
                      marginTop: '1rem', 
                      background: '#f8fafc', 
                      border: '1px solid #cbd5e1', 
                      padding: '1rem', 
                      borderRadius: '8px',
                      color: '#1e3a8a'
                    }}>
                      <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.85rem', marginBottom: '0.25rem' }}>
                        Approved Transaction
                      </div>
                      <p style={{ fontSize: '0.75rem', color: '#475569' }}>
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
