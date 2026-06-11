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
  agentTrace?: string;
}

interface ChatMessage {
  role: 'user' | 'model' | 'system';
  content: string;
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

  const [currentPage, setCurrentPage] = useState<'cases' | 'transactions'>('cases');
  const [isInvestigating, setIsInvestigating] = useState(false);
  const [manualChatHistory, setManualChatHistory] = useState<ChatMessage[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [aiInvestigatorTab, setAiInvestigatorTab] = useState<'timeline' | 'report' | 'chat' | 'audit'>('timeline');

  useEffect(() => {
    setManualChatHistory([]);
    setChatInput('');
    setAiInvestigatorTab('timeline');
  }, [selectedCase?.id]);

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

  // Trigger autonomous agent investigation
  const triggerAgentInvestigation = async (caseId: string) => {
    setIsInvestigating(true);
    try {
      const res = await fetch(`${API_BASE}/cases/${caseId}/investigate`, { method: 'POST' });
      if (res.ok) {
        const data = await res.json();
        // Refetch the case to get updated trace
        const casesRes = await fetch(`${API_BASE}/cases`);
        if (casesRes.ok) {
          const casesData = await casesRes.json();
          const reversed = casesData.reverse();
          setCases(reversed);
          const updated = reversed.find((c: any) => c.id === caseId);
          if (updated) {
            setSelectedCase(updated);
          }
        }
      }
    } catch (e) {
      console.error("Agent run failed:", e);
    } finally {
      setIsInvestigating(false);
    }
  };

  // Send a message in the analyst-agent chat
  const sendChatMessage = async () => {
    if (!chatInput.trim() || !selectedCase) return;
    const userMsg = chatInput;
    setChatInput('');
    const newUserHistory = [...manualChatHistory, { role: 'user', content: userMsg } as ChatMessage];
    setManualChatHistory(newUserHistory);

    try {
      const res = await fetch(`${API_BASE}/cases/${selectedCase.id}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: userMsg,
          history: manualChatHistory.map(h => ({ role: h.role, content: h.content }))
        })
      });
      if (res.ok) {
        const data = await res.json();
        setManualChatHistory(prev => [...prev, { role: 'model', content: data.reply } as ChatMessage]);
        if (data.action === 'FREEZE') {
          updateCaseStatus(selectedCase.id, 'ACCOUNT_FROZEN');
        } else if (data.action === 'DISMISS') {
          updateCaseStatus(selectedCase.id, 'CLOSED');
        }
      }
    } catch (e) {
      console.error("Chat message failed:", e);
    }
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

        <div className="nav-links">
          <button 
            className={`nav-btn ${currentPage === 'cases' ? 'active' : ''}`}
            onClick={() => setCurrentPage('cases')}
          >
            🚨 Alert Cases
          </button>
          <button 
            className={`nav-btn ${currentPage === 'transactions' ? 'active' : ''}`}
            onClick={() => setCurrentPage('transactions')}
          >
            📊 Transactions Feed
          </button>
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

      {currentPage === 'cases' ? (
        <main className="dashboard-grid">
          {/* Column 1: Alert Queue (takes full height of left sidebar) */}
          <section className="column">
            <div className="column-header">
              <h2 className="column-title">Alert cases</h2>
              <span className="stat-bubble danger">{cases.filter(c => c.status === "OPEN").length} Active</span>
            </div>
            <div className="column-content" style={{ padding: '0.5rem' }}>
              {cases.length === 0 ? (
                <div style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '4rem', fontSize: '0.85rem' }}>
                  No active incidents.
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

          {/* Right Column: Split 50/50 side-by-side Workbench & AI Investigator */}
          <section className="column" style={{ background: '#090d16', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1px', borderLeft: '1px solid var(--border-color)' }}>
            {/* Left Workspace Panel: Case Profile & Evidence */}
            <div className="column-content" style={{ borderRight: '1px solid var(--border-color)', height: '100%', overflowY: 'auto' }}>
              <div className="column-title" style={{ marginBottom: '1rem', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.5rem' }}>
                Case Profile & Evidence
              </div>
              {!selectedTxn ? (
                <div className="details-placeholder">
                  <span className="details-placeholder-icon">🔎</span>
                  <p style={{ fontWeight: 600, color: 'var(--text-primary)', marginBottom: '0.25rem' }}>Select an alert case to audit details</p>
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
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.15rem' }}>
                            <span className="compare-title">Transaction Location</span>
                            <span style={{ fontSize: '0.65rem', fontWeight: 700, color: isLocationMismatch ? 'var(--color-danger)' : '#10b981' }}>
                              {isLocationMismatch ? '❌ ANOMALY' : '✅ MATCH'}
                            </span>
                          </div>
                          <div className="compare-val">{selectedTxn.location}</div>
                          <div style={{ fontSize: '0.7rem', opacity: 0.9, marginTop: '0.25rem' }}>
                            Baseline: {selectedUser.frequentLocations.join(", ")}
                          </div>
                        </div>

                        <div className={`compare-box ${isDeviceMismatch ? 'mismatch' : ''}`}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.15rem' }}>
                            <span className="compare-title">Device Used</span>
                            <span style={{ fontSize: '0.65rem', fontWeight: 700, color: isDeviceMismatch ? 'var(--color-danger)' : '#10b981' }}>
                              {isDeviceMismatch ? '❌ ANOMALY' : '✅ MATCH'}
                            </span>
                          </div>
                          <div className="compare-val">{selectedTxn.deviceUsed}</div>
                          <div style={{ fontSize: '0.7rem', opacity: 0.9, marginTop: '0.25rem' }}>
                            Baseline: {selectedUser.frequentDevices.join(", ")}
                          </div>
                        </div>

                        <div className={`compare-box ${isAmountAnomaly ? 'mismatch' : ''}`}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.15rem' }}>
                            <span className="compare-title">Amount</span>
                            <span style={{ fontSize: '0.65rem', fontWeight: 700, color: isAmountAnomaly ? 'var(--color-danger)' : '#10b981' }}>
                              {isAmountAnomaly ? '❌ ANOMALY' : '✅ MATCH'}
                            </span>
                          </div>
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
                        background: 'rgba(255, 255, 255, 0.02)', 
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
                    background: 'rgba(0, 0, 0, 0.2)', 
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
                      <div style={{ textAlign: 'center', background: 'rgba(255,255,255,0.01)', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border-color)' }}>
                        <div style={{ fontSize: '0.65rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Spending Dev</div>
                        <div style={{ fontSize: '0.95rem', fontWeight: 600, color: (selectedTxn.spendingDeviationScore || 0) > 2 ? 'var(--color-danger)' : 'inherit' }}>
                          {selectedTxn.spendingDeviationScore !== undefined && selectedTxn.spendingDeviationScore !== null ? selectedTxn.spendingDeviationScore.toFixed(2) : 'N/A'}
                        </div>
                      </div>
                      <div style={{ textAlign: 'center', background: 'rgba(255,255,255,0.01)', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border-color)' }}>
                        <div style={{ fontSize: '0.65rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Velocity Score</div>
                        <div style={{ fontSize: '0.95rem', fontWeight: 600, color: (selectedTxn.velocityScore || 0) > 10 ? 'var(--color-danger)' : 'inherit' }}>
                          {selectedTxn.velocityScore !== undefined && selectedTxn.velocityScore !== null ? selectedTxn.velocityScore.toFixed(0) : 'N/A'}
                        </div>
                      </div>
                      <div style={{ textAlign: 'center', background: 'rgba(255,255,255,0.01)', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border-color)' }}>
                        <div style={{ fontSize: '0.65rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Geo Anomaly</div>
                        <div style={{ fontSize: '0.95rem', fontWeight: 600, color: (selectedTxn.geoAnomalyScore || 0) > 0.8 ? 'var(--color-danger)' : 'inherit' }}>
                          {selectedTxn.geoAnomalyScore !== undefined && selectedTxn.geoAnomalyScore !== null ? selectedTxn.geoAnomalyScore.toFixed(2) : 'N/A'}
                        </div>
                      </div>
                    </div>
                  </div>

                  {selectedCase && (
                    <div style={{ 
                      border: '1px solid var(--border-color)', 
                      borderRadius: '8px', 
                      padding: '0.75rem',
                      background: 'rgba(0,0,0,0.1)',
                      marginBottom: '1rem'
                    }}>
                      <div style={{ fontWeight: 700, fontSize: '0.8rem', color: 'var(--color-primary)', textTransform: 'uppercase', marginBottom: '0.35rem' }}>AI Explanation</div>
                      <div className="ai-reasoning" style={{ margin: 0 }}>
                        {selectedCase.aiReasoning}
                      </div>
                    </div>
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

            {/* Right Workspace Panel: AI Investigator Console */}
            <div className="column-content" style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.5rem' }}>
                <div className="column-title" style={{ display: 'flex', alignItems: 'center', gap: '0.25rem' }}>🤖 AI Investigator Console</div>
                {selectedCase && (
                  <button 
                    className="btn btn-primary" 
                    style={{ padding: '0.25rem 0.65rem', fontSize: '0.65rem' }}
                    onClick={() => triggerAgentInvestigation(selectedCase.id)}
                    disabled={isInvestigating}
                  >
                    {isInvestigating ? 'Running...' : '▶ Run Agent'}
                  </button>
                )}
              </div>

              {selectedCase ? (
                <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
                  <div className="tab-container" style={{ gap: '0.15rem' }}>
                    <button className={`tab-btn ${aiInvestigatorTab === 'timeline' ? 'active' : ''}`} onClick={() => setAiInvestigatorTab('timeline')}>Timeline Trace</button>
                    <button className={`tab-btn ${aiInvestigatorTab === 'report' ? 'active' : ''}`} onClick={() => setAiInvestigatorTab('report')}>Report Summary</button>
                    <button className={`tab-btn ${aiInvestigatorTab === 'chat' ? 'active' : ''}`} onClick={() => setAiInvestigatorTab('chat')}>Copilot Chat</button>
                    <button className={`tab-btn ${aiInvestigatorTab === 'audit' ? 'active' : ''}`} onClick={() => setAiInvestigatorTab('audit')}>Compliance Audit</button>
                  </div>

                  <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
                    {aiInvestigatorTab === 'timeline' && (
                      <div className="agent-trace-timeline">
                        {(() => {
                          try {
                            const traceList = JSON.parse(selectedCase.agentTrace || '[]');
                            if (traceList.length === 0) {
                              return (
                                <div style={{ textAlign: 'center', color: 'var(--text-muted)', padding: '2rem 1rem', fontSize: '0.75rem' }}>
                                  {isInvestigating ? 'Agent is executing functions...' : 'No agent run trace. Click "Run Agent" to start.'}
                                </div>
                              );
                            }
                            return traceList.map((step: any, sIdx: number) => (
                              <div key={sIdx} className="trace-step">
                                <div className="trace-step-header">
                                  <span>Step {sIdx}: {step.tool}</span>
                                </div>
                                {step.thought && (
                                  <div style={{ fontSize: '0.7rem', color: '#60a5fa', fontStyle: 'italic', marginBottom: '0.25rem' }}>
                                    <strong>Thought:</strong> {step.thought}
                                  </div>
                                )}
                                <div className="trace-step-body">
                                  {step.args && <div style={{ fontSize: '0.65rem', opacity: 0.8, color: 'var(--text-secondary)' }}><strong>Args:</strong> {step.args}</div>}
                                  {step.result && <div style={{ fontSize: '0.65rem', color: '#10b981', marginTop: '0.15rem' }}><strong>Result:</strong> {step.result.substring(0, 300)}{step.result.length > 300 ? '...' : ''}</div>}
                                </div>
                              </div>
                            ));
                          } catch (e) {
                            return <div style={{ fontSize: '0.75rem', color: 'var(--color-danger)' }}>Error parsing trace logs.</div>;
                          }
                        })()}
                      </div>
                    )}

                    {aiInvestigatorTab === 'report' && (
                      <div style={{ overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '0.65rem', height: '100%', fontSize: '0.75rem' }}>
                        {selectedCase.investigationReport ? (() => {
                          try {
                            const report = JSON.parse(selectedCase.investigationReport);
                            const strengthColor = report.evidenceStrength === 'HIGH' ? 'var(--color-danger)' : report.evidenceStrength === 'MEDIUM' ? 'var(--color-warning)' : 'var(--color-primary)';
                            return (
                              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.65rem' }}>
                                <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                                  <span style={{ fontSize: '0.7rem', fontWeight: 700, padding: '0.2rem 0.5rem', borderRadius: '4px', backgroundColor: strengthColor, color: 'white' }}>
                                    Evidence: {report.evidenceStrength}
                                  </span>
                                  <span style={{ fontSize: '0.7rem', fontWeight: 700, padding: '0.2rem 0.5rem', borderRadius: '4px', border: '1px solid var(--border-color)', background: 'rgba(255,255,255,0.05)' }}>
                                    Recommended Action: {report.recommendedAction}
                                  </span>
                                </div>
                                <div style={{ background: 'rgba(255,255,255,0.02)', padding: '0.75rem', borderRadius: '6px', border: '1px solid var(--border-color)' }}>
                                  <strong style={{ color: '#60a5fa' }}>Summary Findings:</strong>
                                  <p style={{ margin: '0.25rem 0 0', lineHeight: '1.4' }}>{report.investigationSummary}</p>
                                </div>
                                <div style={{ background: 'rgba(255,255,255,0.02)', padding: '0.75rem', borderRadius: '6px', border: '1px solid var(--border-color)' }}>
                                  <strong style={{ color: '#60a5fa' }}>Audit Log Statement:</strong>
                                  <p style={{ margin: '0.25rem 0 0', lineHeight: '1.4', fontStyle: 'italic' }}>{report.auditTrail}</p>
                                </div>
                                <div style={{ background: 'rgba(16, 185, 129, 0.08)', padding: '0.75rem', borderRadius: '6px', border: '1px dashed rgba(16, 185, 129, 0.3)' }}>
                                  <strong style={{ color: '#10b981' }}>Customer Alert SMS:</strong>
                                  <p style={{ margin: '0.25rem 0 0', lineHeight: '1.4' }}>"{report.customerMessage}"</p>
                                </div>
                              </div>
                            );
                          } catch (e) {
                            return <div style={{ padding: '0.5rem', background: 'rgba(0,0,0,0.1)' }}>{selectedCase.investigationReport}</div>;
                          }
                        })() : (
                          <div style={{ textAlign: 'center', color: 'var(--text-muted)', padding: '2rem 1rem' }}>
                            No autonomous agent report compiled. Run investigation first.
                          </div>
                        )}
                      </div>
                    )}

                    {aiInvestigatorTab === 'chat' && (
                      <div className="chat-container">
                        <div className="chat-messages">
                          <div className="chat-msg system">
                            Incident Investigation Copilot active. Ask me about Case {selectedCase.transactionId}'s anomalies, baseline comparisons, or ask me to freeze the account.
                          </div>
                          {manualChatHistory.map((msg, msgIdx) => (
                            <div key={msgIdx} className={`chat-msg ${msg.role}`}>
                              {msg.content}
                            </div>
                          ))}
                        </div>
                        <div className="chat-input-row">
                          <input
                            type="text"
                            placeholder="Ask the copilot (e.g. 'Show transaction history')"
                            value={chatInput}
                            onChange={(e) => setChatInput(e.target.value)}
                            onKeyDown={(e) => { if (e.key === 'Enter') sendChatMessage(); }}
                          />
                          <button onClick={sendChatMessage} disabled={!chatInput.trim()}>Send</button>
                        </div>
                      </div>
                    )}

                    {aiInvestigatorTab === 'audit' && (
                      <div style={{ 
                        background: '#040711', 
                        color: '#38bdf8', 
                        fontFamily: 'var(--font-mono)', 
                        fontSize: '0.7rem', 
                        padding: '0.75rem', 
                        borderRadius: '6px', 
                        height: '100%', 
                        overflowY: 'auto' 
                      }}>
                        <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                          {(() => {
                            try {
                              if (!selectedCase.regulatoryAuditRecord) return "No regulatory compliance trail generated.";
                              const obj = JSON.parse(selectedCase.regulatoryAuditRecord);
                              return JSON.stringify(obj, null, 2);
                            } catch (e) {
                              return selectedCase.regulatoryAuditRecord;
                            }
                          })()}
                        </pre>
                      </div>
                    )}
                  </div>
                </div>
              ) : (
                <div style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '4rem', fontSize: '0.8rem' }}>
                  Select an alert case to open the AI Investigator panel.
                </div>
              )}
            </div>
          </section>
        </main>
      ) : (
        <main className="dashboard-grid" style={{ gridTemplateColumns: selectedTxn ? '1fr 380px' : '1fr' }}>
          {/* Full Screen Live Transaction Log */}
          <section className="column">
            <div className="column-header">
              <h2 className="column-title">Real-Time Transactions Feed</h2>
              <span className="stat-bubble">{totalCount.toLocaleString()} rows</span>
            </div>
            <div className="column-content" onScroll={handleScroll}>
              {transactions.length === 0 ? (
                <div style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '4rem', fontSize: '0.9rem' }}>
                  No transaction data. Trigger live CSV feed to start.
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
                        <th>Device Used</th>
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
                              setSelectedTxn(t);
                              const matchingCase = cases.find(c => c.transactionId === t.transactionId);
                              if (matchingCase) {
                                setSelectedCase(matchingCase);
                                setSelectedUser(usersMap[matchingCase.accountId] || null);
                              } else {
                                setSelectedCase(null);
                                setSelectedUser(usersMap[t.senderAccount] || null);
                              }
                            }}
                          >
                            <td style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                              {t.timestamp.split('T')[1]?.substring(0, 8) || t.timestamp}
                            </td>
                            <td style={{ fontWeight: 600 }}>{t.transactionId}</td>
                            <td style={{ fontFamily: 'var(--font-mono)' }}>{t.senderAccount}</td>
                            <td style={{ fontWeight: 700, color: t.isFraud ? 'var(--color-danger)' : 'inherit' }}>
                              ${t.amount.toFixed(2)}
                            </td>
                            <td>{t.location}</td>
                            <td style={{ textTransform: 'capitalize' }}>{t.deviceUsed}</td>
                            <td style={{ color: 'var(--text-secondary)' }}>{t.merchantCategory}</td>
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

          {/* Quick Drawer Panel for Transaction Baseline Verification */}
          {selectedTxn && (
            <section className="column" style={{ background: '#0a0e1a', borderLeft: '1px solid var(--border-color)' }}>
              <div className="column-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <h2 className="column-title">Inspect Details</h2>
                <button 
                  className="btn btn-secondary" 
                  style={{ padding: '0.2rem 0.5rem', fontSize: '0.65rem' }}
                  onClick={() => { setSelectedTxn(null); setSelectedCase(null); }}
                >
                  Close
                </button>
              </div>
              <div className="column-content" style={{ padding: '1rem' }}>
                <div style={{ marginBottom: '1.25rem' }}>
                  <h3 style={{ fontSize: '1.05rem', fontWeight: 700, color: 'var(--text-primary)' }}>{selectedTxn.transactionId}</h3>
                  <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Type: {selectedTxn.transactionType} | Category: {selectedTxn.merchantCategory}</p>
                </div>

                <div className="section-title">Baseline Verification</div>
                {selectedUser ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                    <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      <strong>Cardholder:</strong> {selectedUser.name} (Acc: {selectedUser.accountId})
                    </div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                      <div className={`compare-box ${isLocationMismatch ? 'mismatch' : ''}`}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.65rem', fontWeight: 700 }}>
                          <span>Location</span>
                          <span>{isLocationMismatch ? '❌ ANOMALY' : '✅ MATCH'}</span>
                        </div>
                        <div className="compare-val" style={{ margin: '0.15rem 0', fontSize: '0.75rem' }}>{selectedTxn.location}</div>
                        <div style={{ fontSize: '0.65rem', opacity: 0.8 }}>Baseline: {selectedUser.frequentLocations.join(', ')}</div>
                      </div>

                      <div className={`compare-box ${isDeviceMismatch ? 'mismatch' : ''}`}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.65rem', fontWeight: 700 }}>
                          <span>Device</span>
                          <span>{isDeviceMismatch ? '❌ ANOMALY' : '✅ MATCH'}</span>
                        </div>
                        <div className="compare-val" style={{ margin: '0.15rem 0', fontSize: '0.75rem' }}>{selectedTxn.deviceUsed}</div>
                        <div style={{ fontSize: '0.65rem', opacity: 0.8 }}>Baseline: {selectedUser.frequentDevices.join(', ')}</div>
                      </div>

                      <div className={`compare-box ${isAmountAnomaly ? 'mismatch' : ''}`}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.65rem', fontWeight: 700 }}>
                          <span>Amount</span>
                          <span>{isAmountAnomaly ? '❌ ANOMALY' : '✅ MATCH'}</span>
                        </div>
                        <div className="compare-val" style={{ margin: '0.15rem 0', fontSize: '0.75rem' }}>${selectedTxn.amount.toFixed(2)}</div>
                        <div style={{ fontSize: '0.65rem', opacity: 0.8 }}>Avg: ${selectedUser.averageTransactionValue.toFixed(2)}</div>
                      </div>
                    </div>
                  </div>
                ) : (
                  <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', background: 'rgba(255,255,255,0.02)', padding: '0.75rem', borderRadius: '6px', border: '1px solid var(--border-color)' }}>
                    Baseline behavior not compiled for this bulk cardholder (Acc: {selectedTxn.senderAccount}).
                  </div>
                )}

                {selectedCase && (
                  <div style={{ marginTop: '1.25rem' }}>
                    <span className="badge badge-open" style={{ marginBottom: '0.5rem' }}>🚨 Linked Alert Incident</span>
                    <div className="ai-reasoning">
                      <strong>AI Flag Reasoning:</strong>
                      <p style={{ marginTop: '0.25rem', lineHeight: '1.3' }}>{selectedCase.aiReasoning}</p>
                    </div>
                  </div>
                )}
              </div>
            </section>
          )}
        </main>
      )}
    </>
  );
}

export default App;
