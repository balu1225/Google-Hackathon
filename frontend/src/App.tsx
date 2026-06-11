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

interface TraceStep {
  step: number;
  type: 'GOAL' | 'TOOL_CALL' | 'TOOL_RESULT' | 'REASONING' | 'COMPLETE' | 'ERROR';
  tool?: string;
  args?: string;
  result?: string;
  content?: string;
  timestamp?: string;
  durationMs?: number;
}

const BACKEND_URL = import.meta.env.VITE_BACKEND_URL || "http://localhost:8080";
const API_BASE = `${BACKEND_URL}/api`;
const WS_URL = BACKEND_URL.replace(/^http/, "ws") + "/ws/stream";

const normalizeRisk = (score: number) => {
  if (score === undefined || score === null) return 0;
  return score > 1.0 ? score / 100 : score;
};


const timeAgo = (iso: string) => {
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
};


const getRiskColor = (risk: number) => {
  if (risk >= 0.7) return '#ef4444';
  if (risk >= 0.4) return '#f59e0b';
  return '#3b82f6';
};

const STATUS_LABELS: Record<string, string> = {
  OPEN: 'Under Review',
  ACCOUNT_FROZEN: 'Account Frozen',
  CLOSED: 'Dismissed',
  INVESTIGATING: 'Investigating',
};

// ── Live Investigation Timeline Component ──

const TOOL_META: Record<string, { label: string; icon: string; color: string }> = {
  get_user_baseline:           { label: 'Reviewing account behavior profile',   icon: '👤', color: '#60a5fa' },
  get_case_transactions:       { label: 'Analyzing transaction history',         icon: '📊', color: '#a78bfa' },
  get_receiver_profile:        { label: 'Checking receiver for mule patterns',   icon: '🎯', color: '#f97316' },
  get_open_cases:              { label: 'Scanning for related fraud cases',      icon: '🔍', color: '#fbbf24' },
  submit_investigation_report: { label: 'Submitting final investigation report', icon: '📋', color: '#34d399' },
  update_case_status:          { label: 'Applying case decision',                icon: '⚡', color: '#f43f5e' },
  get_fraud_network_stats:     { label: 'Analyzing fraud network statistics',    icon: '🕸️', color: '#22d3ee' },
};

function LiveInvestigationTimeline({
  steps,
  isLive,
  expandedSteps,
  setExpandedSteps,
  liveEndRef,
}: {
  steps: TraceStep[];
  isLive: boolean;
  expandedSteps: Set<string>;
  setExpandedSteps: React.Dispatch<React.SetStateAction<Set<string>>>;
  liveEndRef: React.RefObject<HTMLDivElement | null>;
}) {
  const pairs: { call: TraceStep; result?: TraceStep; idx: number }[] = [];
  const reasoningSteps: TraceStep[] = [];
  let completeStep: TraceStep | undefined;

  steps.forEach(s => {
    if (s.type === 'TOOL_CALL') {
      pairs.push({ call: s, idx: pairs.length });
    } else if (s.type === 'TOOL_RESULT' && pairs.length > 0) {
      const last = pairs[pairs.length - 1];
      if (!last.result) last.result = s;
    } else if (s.type === 'REASONING') {
      reasoningSteps.push(s);
    } else if (s.type === 'COMPLETE') {
      completeStep = s;
    }
  });

  const toggleStep = (key: string) => {
    setExpandedSteps(prev => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  };

  const activePairIdx = isLive ? pairs.findLastIndex(p => !p.result) : -1;
  const fmtDuration = (ms: number) => ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
  const hasContent = pairs.length > 0 || reasoningSteps.length > 0 || !!completeStep || (isLive && pairs.length === 0);

  return (
    <div className="ai-chat-window">
      {/* Chat header */}
      <div className="ai-chat-header">
        <div className="ai-avatar">🤖</div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: '0.72rem', fontWeight: 700, color: 'var(--text-primary)' }}>FraudShield AI Agent</div>
          {isLive ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.35rem' }}>
              <div className="live-dot" />
              <span style={{ fontSize: '0.6rem', color: '#60a5fa' }}>Thinking<span className="thinking-ellipsis" /></span>
            </div>
          ) : hasContent ? (
            <span style={{ fontSize: '0.6rem', color: 'var(--text-muted)' }}>
              Investigation complete · {pairs.length} step{pairs.length !== 1 ? 's' : ''}
            </span>
          ) : (
            <span style={{ fontSize: '0.6rem', color: 'var(--text-muted)' }}>Ready to investigate</span>
          )}
        </div>
        <span className="model-badge">Gemini 2.5</span>
      </div>

      {/* Message list */}
      <div className="ai-chat-messages">

        {/* Starting state */}
        {isLive && pairs.length === 0 && (
          <div className="ai-message live-step">
            <div className="ai-message-bubble active">
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <div className="spinner" />
                <span style={{ fontSize: '0.75rem', color: '#60a5fa', fontWeight: 600 }}>Gathering evidence</span>
                <span className="thinking-badge">starting…</span>
              </div>
              <div className="thinking-progress" />
            </div>
          </div>
        )}

        {/* Tool call pairs */}
        {pairs.map(({ call, result, idx }) => {
          const meta = TOOL_META[call.tool || ''] || { label: call.tool || 'Tool call', icon: '🔧', color: '#94a3b8' };
          const isActive = isLive && idx === activePairIdx;
          const isDone = !!result;
          const key = `tool-${idx}`;
          const isExpanded = expandedSteps.has(key);
          const shortResult = result?.result
            ? result.result.split('\n').filter(l => l.trim()).slice(0, 5).join('\n')
            : '';
          const durationMs = result?.durationMs;

          return (
            <div key={key} className="ai-message live-step">
              <div
                className={`ai-message-bubble ${isActive ? 'active' : isDone ? 'done' : 'pending'}`}
                onClick={() => isDone && toggleStep(key)}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  {isActive ? (
                    <div className="spinner" />
                  ) : isDone ? (
                    <span style={{ fontSize: '0.75rem', color: '#22c55e', lineHeight: 1 }}>✓</span>
                  ) : (
                    <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', lineHeight: 1 }}>○</span>
                  )}
                  <span style={{ fontSize: '1rem', lineHeight: 1 }}>{meta.icon}</span>
                  <span style={{
                    fontSize: '0.75rem', fontWeight: 600, flex: 1, minWidth: 0,
                    color: isActive ? meta.color : isDone ? 'var(--text-primary)' : 'var(--text-muted)',
                  }}>{meta.label}</span>
                  {isActive && <span className="thinking-badge">thinking…</span>}
                  {isDone && durationMs !== undefined && <span className="duration-badge">{fmtDuration(durationMs)}</span>}
                  {isDone && (
                    <span style={{ fontSize: '0.58rem', color: 'var(--text-muted)', marginLeft: '0.1rem' }}>
                      {isExpanded ? '▲' : '▼'}
                    </span>
                  )}
                </div>
                {isActive && <div className="thinking-progress" />}
                {isDone && isExpanded && shortResult && (
                  <div className="ai-result-block">{shortResult}</div>
                )}
              </div>
            </div>
          );
        })}

        {/* Reasoning */}
        {reasoningSteps.map((r, i) => (
          <div key={`reasoning-${i}`} className="ai-message live-step">
            <div className="ai-message-bubble reasoning">
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', marginBottom: '0.45rem' }}>
                <span style={{ fontSize: '0.9rem' }}>🧠</span>
                <span style={{ fontSize: '0.6rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em', color: '#c084fc' }}>AI Conclusion</span>
              </div>
              <p style={{ fontSize: '0.78rem', color: 'var(--text-primary)', lineHeight: 1.6, margin: 0 }}>
                {(r.content || '').length > 420 ? (r.content || '').substring(0, 420) + '…' : r.content}
              </p>
            </div>
          </div>
        ))}

        {/* Complete */}
        {completeStep && (
          <div className="ai-message live-step">
            <div className="ai-message-bubble complete">
              <span style={{ fontSize: '1rem' }}>✅</span>
              <span style={{ fontSize: '0.76rem', fontWeight: 600, color: '#22c55e', flex: 1 }}>Investigation complete</span>
              <span style={{ fontSize: '0.62rem', color: 'var(--text-muted)' }}>
                {pairs.length} tool{pairs.length !== 1 ? 's' : ''} used
              </span>
            </div>
          </div>
        )}

        <div ref={liveEndRef} />
      </div>
    </div>
  );
}

function App() {
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [visibleCount, setVisibleCount] = useState(30);
  const [totalCount, setTotalCount] = useState<number>(0);
  const [cases, setCases] = useState<FraudCase[]>([]);
  const [usersMap, setUsersMap] = useState<Record<string, User>>({});
  const [selectedCase, setSelectedCase] = useState<FraudCase | null>(null);
  const [selectedUser, setSelectedUser] = useState<User | null>(null);
  const [selectedTxn, setSelectedTxn] = useState<Transaction | null>(null);
  const [currentPage, setCurrentPage] = useState<'cases' | 'transactions'>('cases');
  const [isInvestigating, setIsInvestigating] = useState(false);
  const [liveSteps, setLiveSteps] = useState<TraceStep[]>([]);
  const [investigatingCaseId, setInvestigatingCaseId] = useState<string | null>(null);
  const [expandedSteps, setExpandedSteps] = useState<Set<string>>(new Set());
  const liveEndRef = useRef<HTMLDivElement | null>(null);
  const [chatExpanded, setChatExpanded] = useState(false);
  const [manualChatHistory, setManualChatHistory] = useState<ChatMessage[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [isIngesting, setIsIngesting] = useState(false);
  const [wsStatus, setWsStatus] = useState<'connecting' | 'connected' | 'disconnected'>('connecting');
  const wsRef = useRef<WebSocket | null>(null);
  const chatEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    setManualChatHistory([]);
    setChatInput('');
    setChatExpanded(false);
  }, [selectedCase?.id]);

  useEffect(() => {
    if (chatExpanded) chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [manualChatHistory, chatExpanded]);

  useEffect(() => {
    liveEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [liveSteps]);

  useEffect(() => {
    fetch(`${API_BASE}/cases`).then(r => r.json()).then(d => setCases(d.reverse())).catch(() => {});
    fetch(`${API_BASE}/transactions/count`).then(r => r.json()).then(d => setTotalCount(d)).catch(() => {});
    fetch(`${API_BASE}/transactions`).then(r => r.json()).then(d => setTransactions(d.reverse().slice(0, 500))).catch(() => {});
    fetch(`${API_BASE}/users`).then(r => r.json()).then((d: User[]) => {
      const map: Record<string, User> = {};
      d.forEach(u => { map[u.accountId] = u; });
      setUsersMap(map);
    }).catch(() => {});
  }, []);

  useEffect(() => {
    connectWS();
    return () => { if (wsRef.current) wsRef.current.close(); };
  }, []);

  const connectWS = () => {
    setWsStatus('connecting');
    const ws = new WebSocket(WS_URL);
    wsRef.current = ws;
    ws.onopen = () => setWsStatus('connected');
    ws.onclose = e => { setWsStatus('disconnected'); if (e.code !== 1000) setTimeout(connectWS, 3000); };
    ws.onerror = () => setWsStatus('disconnected');
    ws.onmessage = e => {
      try {
        const p = JSON.parse(e.data);
        if (p.type === 'TRANSACTION') {
          setTransactions(prev => {
            if (prev.some(t => t.transactionId === p.data.transactionId)) return prev;
            setTotalCount(c => c + 1);
            return [p.data, ...prev].slice(0, 500);
          });
        } else if (p.type === 'FRAUD_CASE') {
          setCases(prev => {
            if (prev.some(c => c.id === p.data.id || c.transactionId === p.data.transactionId)) return prev;
            return [p.data, ...prev].slice(0, 500);
          });
        } else if (p.type === 'CASE_UPDATE') {
          setCases(prev => prev.map(c => c.id === p.data.id ? p.data : c));
          setSelectedCase(prev => prev?.id === p.data.id ? p.data : prev);
        } else if (p.type === 'INVESTIGATION_STEP') {
          if (p.caseId === investigatingCaseId || investigatingCaseId) {
            setLiveSteps(prev => [...prev, p.step as TraceStep]);
          }
        } else if (p.type === 'SYSTEM' && p.message === 'Ingestion stopped') {
          setIsIngesting(false);
        } else if (p.type === 'BULK_LOAD_COMPLETE') {
          setIsIngesting(false);
          fetch(`${API_BASE}/cases`).then(r => r.json()).then(d => setCases(d.reverse()));
          fetch(`${API_BASE}/transactions/count`).then(r => r.json()).then(d => setTotalCount(d));
          fetch(`${API_BASE}/transactions`).then(r => r.json()).then(d => setTransactions(d.reverse().slice(0, 500)));
        }
      } catch { if (e.data.includes('Connected')) setWsStatus('connected'); }
    };
  };

  const startIngestion = () => { setIsIngesting(true); fetch(`${API_BASE}/ingest/start`, { method: 'POST' }).catch(() => setIsIngesting(false)); };
  const stopIngestion = () => { fetch(`${API_BASE}/ingest/stop`, { method: 'POST' }).then(r => { if (r.ok) setIsIngesting(false); }); };
  const resetAndReingest = async () => {
    if (!window.confirm('This will clear all cases, transactions and users then restart the live feed. Continue?')) return;
    await fetch(`${API_BASE}/debug/clear`, { method: 'DELETE' });
    setCases([]); setTransactions([]); setUsersMap({}); setTotalCount(0);
    setSelectedCase(null); setSelectedTxn(null); setSelectedUser(null);
    startIngestion();
  };

  const fetchUser = async (accountId: string): Promise<User | null> => {
    if (usersMap[accountId]) return usersMap[accountId];
    try {
      const r = await fetch(`${API_BASE}/users/${accountId}`);
      if (r.ok) {
        const u: User = await r.json();
        setUsersMap(prev => ({ ...prev, [accountId]: u }));
        return u;
      }
    } catch {}
    return null;
  };

  const selectCase = async (c: FraudCase) => {
    setSelectedCase(c);
    setSelectedTxn(null);
    const user = await fetchUser(c.accountId);
    setSelectedUser(user);
    const local = transactions.find(t => t.transactionId === c.transactionId);
    if (local) { setSelectedTxn(local); return; }
    try {
      const r = await fetch(`${API_BASE}/transactions`);
      const txns: Transaction[] = await r.json();
      const found = txns.find(t => t.transactionId === c.transactionId);
      if (found) setSelectedTxn(found);
    } catch {}
  };

  const updateCaseStatus = (caseId: string, status: string) => {
    fetch(`${API_BASE}/cases/${caseId}/status?status=${status}`, { method: 'PUT' })
      .then(r => r.ok ? r.json() : null)
      .then((updated: FraudCase) => {
        if (!updated) return;
        setCases(prev => prev.map(c => c.id === caseId ? updated : c));
        setSelectedCase(updated);
      });
  };

  const triggerAgentInvestigation = async (caseId: string) => {
    setIsInvestigating(true);
    setLiveSteps([]);
    setInvestigatingCaseId(caseId);
    setExpandedSteps(new Set());
    try {
      const res = await fetch(`${API_BASE}/cases/${caseId}/investigate`, { method: 'POST' });
      if (res.ok) {
        await res.json();
        const r = await fetch(`${API_BASE}/cases`);
        if (r.ok) {
          const all = (await r.json()).reverse();
          setCases(all);
          const updated = all.find((c: FraudCase) => c.id === caseId);
          if (updated) setSelectedCase(updated);
        }
      }
    } catch (e) { console.error('Agent run failed:', e); }
    finally {
      setIsInvestigating(false);
      setInvestigatingCaseId(null);
    }
  };

  const [chatLoading, setChatLoading] = useState(false);

  const sendChatMessage = async () => {
    if (!chatInput.trim() || !selectedCase || chatLoading) return;
    const msg = chatInput;
    setChatInput('');
    setManualChatHistory(p => [...p, { role: 'user', content: msg } as ChatMessage]);
    setChatLoading(true);
    try {
      const r = await fetch(`${API_BASE}/cases/${selectedCase.id}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: msg, history: manualChatHistory.map(h => ({ role: h.role, content: h.content })) })
      });
      if (r.ok) {
        const data = await r.json();
        setManualChatHistory(p => [...p, { role: 'model', content: data.reply } as ChatMessage]);
        if (data.action === 'FREEZE') updateCaseStatus(selectedCase.id, 'ACCOUNT_FROZEN');
        else if (data.action === 'DISMISS') updateCaseStatus(selectedCase.id, 'CLOSED');
      } else {
        setManualChatHistory(p => [...p, { role: 'error' as any, content: `Server error ${r.status} — please try again.` } as ChatMessage]);
      }
    } catch (e) {
      setManualChatHistory(p => [...p, { role: 'error' as any, content: 'Could not reach the server. Check your connection.' } as ChatMessage]);
    } finally {
      setChatLoading(false);
    }
  };

  const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const t = e.currentTarget;
    if (t.scrollHeight - t.scrollTop <= t.clientHeight + 50) setVisibleCount(p => Math.min(p + 30, transactions.length));
  };

  const getFlagDetails = (t: Transaction) => {
    const user = usersMap[t.senderAccount];
    if (!user) return { count: 0, list: [] as string[] };
    const list: string[] = [];
    if (!user.frequentLocations.includes(t.location)) list.push('Location');
    if (!user.frequentDevices.includes(t.deviceUsed)) list.push('Device');
    if (t.amount > user.averageTransactionValue * 2) list.push('Amount');
    return { count: list.length, list };
  };

  const openCasesCount = cases.filter(c => c.status === 'OPEN').length;
  const risk = selectedCase ? normalizeRisk(selectedCase.riskScore) : 0;
  const isLocationMismatch = !!(selectedTxn && selectedUser && !selectedUser.frequentLocations.includes(selectedTxn.location));
  const isDeviceMismatch = !!(selectedTxn && selectedUser && !selectedUser.frequentDevices.includes(selectedTxn.deviceUsed));
  const isAmountAnomaly = !!(selectedTxn && selectedUser && selectedTxn.amount > selectedUser.averageTransactionValue * 2);

  const parseTrace = (raw: string | undefined): TraceStep[] => {
    if (!raw) return [];
    try { return JSON.parse(raw); } catch { return []; }
  };

  const parseReport = (raw: string | undefined) => {
    if (!raw) return null;
    try { return JSON.parse(raw); } catch { return null; }
  };

  const buildInvestigationSteps = (trace: TraceStep[]) => {
    const steps: { call: TraceStep; result?: TraceStep }[] = [];
    for (const s of trace) {
      if (s.type === 'TOOL_CALL') steps.push({ call: s });
      else if (s.type === 'TOOL_RESULT' && steps.length > 0) steps[steps.length - 1].result = s;
    }
    return { steps };
  };

  return (
    <>
      <header>
        <div className="logo-container">
          <div className="logo-icon">🛡️</div>
          <div>
            <div className="logo-text">FraudShield</div>
            <div style={{ fontSize: '0.7rem', color: 'var(--text-secondary)' }}>Real-Time AI Fraud Intelligence</div>
          </div>
        </div>

        <div className="nav-links">
          <button className={`nav-btn ${currentPage === 'cases' ? 'active' : ''}`} onClick={() => setCurrentPage('cases')}>🚨 Alert Cases</button>
          <button className={`nav-btn ${currentPage === 'transactions' ? 'active' : ''}`} onClick={() => setCurrentPage('transactions')}>📊 Live Transactions</button>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '1.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', backgroundColor: wsStatus === 'connected' ? '#22c55e' : '#ef4444', display: 'inline-block' }} />
            <span style={{ fontSize: '0.7rem', color: 'var(--text-secondary)', textTransform: 'uppercase', fontWeight: 600 }}>{wsStatus}</span>
          </div>
          {isIngesting
            ? <button onClick={stopIngestion} className="btn btn-danger">⏹ Stop Stream</button>
            : <button onClick={startIngestion} className="btn btn-primary">▶ Stream Feed</button>
          }
          <button onClick={resetAndReingest} className="btn btn-secondary" style={{ fontSize: '0.65rem', opacity: 0.7 }} title="Clear all data and restart live feed">⟳ Reset Demo</button>
        </div>
      </header>

      {currentPage === 'cases' ? (
        <main className="dashboard-grid">
          {/* Alert Queue */}
          <section className="column">
            <div className="column-header">
              <h2 className="column-title">Alert Queue</h2>
              <span className="stat-bubble danger">{openCasesCount} Active</span>
            </div>
            <div className="column-content" style={{ padding: '0.5rem' }}>
              {cases.length === 0 ? (
                <div style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '4rem', fontSize: '0.85rem' }}>
                  No alerts yet. Start the stream feed to detect fraud.
                </div>
              ) : cases.map(c => (
                <div
                  key={c.id}
                  className={`alert-card ${c.status.toLowerCase()}-case ${selectedCase?.id === c.id ? 'active' : ''}`}
                  onClick={() => selectCase(c)}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.35rem' }}>
                    <span style={{ fontWeight: 600, fontSize: '0.8rem' }}>{c.transactionId}</span>
                    <span className={`badge badge-${c.status.toLowerCase()}`}>{STATUS_LABELS[c.status] || c.status}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                    <span>{c.accountId}</span>
                    <span style={{ fontWeight: 700, color: getRiskColor(normalizeRisk(c.riskScore)) }}>
                      {(normalizeRisk(c.riskScore) * 100).toFixed(0)}% risk
                    </span>
                  </div>
                  <div style={{ fontSize: '0.68rem', color: 'var(--text-muted)', marginTop: '0.2rem' }}>{timeAgo(c.detectedAt)}</div>
                </div>
              ))}
            </div>
          </section>

          {/* Unified Case Intelligence Panel */}
          <section className="column" style={{ background: '#080c17', borderLeft: '1px solid var(--border-color)', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
            {!selectedCase ? (
              <div className="details-placeholder" style={{ margin: 'auto' }}>
                <span className="details-placeholder-icon">🔎</span>
                <p style={{ fontWeight: 600, color: 'var(--text-primary)', marginBottom: '0.25rem' }}>Select an alert to investigate</p>
                <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Click any case in the queue to see a full fraud breakdown.</p>
              </div>
            ) : (() => {
              const trace = parseTrace(selectedCase.agentTrace);
              const report = parseReport(selectedCase.investigationReport);
              const { steps: invSteps } = buildInvestigationSteps(trace);
              const riskPct = (risk * 100).toFixed(0);
              const riskColor = getRiskColor(risk);

              return (
                <div style={{ flex: 1, overflowY: 'auto', padding: '1.25rem' }}>

                  {/* ── 1. Case Header ── */}
                  <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start', marginBottom: '1.25rem' }}>
                    {/* Risk Gauge */}
                    <div style={{
                      minWidth: 64, height: 64, borderRadius: '50%',
                      background: `conic-gradient(${riskColor} ${risk * 360}deg, rgba(255,255,255,0.05) 0deg)`,
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      boxShadow: `0 0 12px ${riskColor}44`
                    }}>
                      <div style={{ width: 46, height: 46, borderRadius: '50%', background: '#080c17', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
                        <span style={{ fontSize: '0.85rem', fontWeight: 800, color: riskColor, lineHeight: 1 }}>{riskPct}%</span>
                        <span style={{ fontSize: '0.5rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>Risk</span>
                      </div>
                    </div>

                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
                        <span style={{ fontWeight: 700, fontSize: '1rem', color: 'var(--text-primary)' }}>{selectedCase.transactionId}</span>
                        <span className={`badge badge-${selectedCase.status.toLowerCase()}`}>{STATUS_LABELS[selectedCase.status] || selectedCase.status}</span>
                      </div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: '0.2rem' }}>
                        Account <strong style={{ color: 'var(--text-primary)' }}>{selectedCase.accountId}</strong>
                        {selectedUser && <> · {selectedUser.frequentLocations[0] || 'Unknown'} based user</>}
                      </div>
                      <div style={{ fontSize: '0.68rem', color: 'var(--text-muted)', marginTop: '0.15rem' }}>Detected {timeAgo(selectedCase.detectedAt)}</div>
                    </div>
                  </div>

                  {/* ── 2. What Happened ── */}
                  {selectedCase.aiReasoning && (
                    <div style={{ background: 'rgba(59,130,246,0.06)', border: '1px solid rgba(59,130,246,0.2)', borderRadius: 8, padding: '0.9rem 1rem', marginBottom: '1rem' }}>
                      <div style={{ fontSize: '0.65rem', fontWeight: 700, textTransform: 'uppercase', color: '#60a5fa', letterSpacing: '0.05em', marginBottom: '0.4rem' }}>📖 What happened</div>
                      <p style={{ fontSize: '0.82rem', color: 'var(--text-primary)', lineHeight: 1.55, margin: 0 }}>
                        {selectedCase.aiReasoning}
                      </p>
                    </div>
                  )}

                  {/* ── 3. Anomaly Signals ── */}
                  {selectedTxn && selectedUser && (
                    <div style={{ marginBottom: '1rem' }}>
                      <div style={{ fontSize: '0.65rem', fontWeight: 700, textTransform: 'uppercase', color: 'var(--text-secondary)', letterSpacing: '0.05em', marginBottom: '0.5rem' }}>⚠️ Anomaly Signals</div>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '0.5rem' }}>
                        {[
                          { label: 'Location', current: selectedTxn.location, baseline: `Usually: ${selectedUser.frequentLocations.slice(0,2).join(', ')}`, anomaly: isLocationMismatch },
                          { label: 'Device', current: selectedTxn.deviceUsed, baseline: `Usually: ${selectedUser.frequentDevices.slice(0,2).join(', ')}`, anomaly: isDeviceMismatch },
                          { label: 'Amount', current: `$${selectedTxn.amount.toFixed(2)}`, baseline: `Avg: $${selectedUser.averageTransactionValue.toFixed(0)}`, anomaly: isAmountAnomaly },
                        ].map(({ label, current, baseline, anomaly }) => (
                          <div key={label} style={{
                            background: anomaly ? 'rgba(239,68,68,0.06)' : 'rgba(34,197,94,0.04)',
                            border: `1px solid ${anomaly ? 'rgba(239,68,68,0.3)' : 'rgba(34,197,94,0.15)'}`,
                            borderRadius: 6, padding: '0.6rem 0.7rem'
                          }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.25rem' }}>
                              <span style={{ fontSize: '0.65rem', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase' }}>{label}</span>
                              <span style={{ fontSize: '0.6rem', fontWeight: 700, color: anomaly ? '#ef4444' : '#22c55e' }}>
                                {anomaly ? '⚠ Unusual' : '✓ Normal'}
                              </span>
                            </div>
                            <div style={{ fontSize: '0.82rem', fontWeight: 700, color: anomaly ? '#ef4444' : 'var(--text-primary)', marginBottom: '0.15rem' }}>{current}</div>
                            <div style={{ fontSize: '0.65rem', color: 'var(--text-muted)' }}>{baseline}</div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* ── 4. AI Investigation ── */}
                  <div style={{ marginBottom: '1rem' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.65rem' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.45rem' }}>
                        <span style={{ fontSize: '0.65rem', fontWeight: 700, textTransform: 'uppercase', color: 'var(--text-secondary)', letterSpacing: '0.05em' }}>AI Investigation</span>
                        {isInvestigating && <span className="live-dot" />}
                      </div>
                      <button
                        className={`btn-investigate${isInvestigating ? ' running' : ''}`}
                        onClick={() => triggerAgentInvestigation(selectedCase.id)}
                        disabled={isInvestigating}
                      >
                        {isInvestigating ? (
                          <><div className="spinner" style={{ width: 10, height: 10, borderWidth: 1.5 }} /> Investigating…</>
                        ) : liveSteps.length > 0 || invSteps.length > 0 ? (
                          <>↺ Re-investigate</>
                        ) : (
                          <>✦ Investigate</>
                        )}
                      </button>
                    </div>

                    {/* Chat-style investigation window */}
                    {(isInvestigating ? liveSteps : trace).length > 0 ? (
                      <LiveInvestigationTimeline
                        steps={isInvestigating ? liveSteps : trace}
                        isLive={isInvestigating}
                        expandedSteps={expandedSteps}
                        setExpandedSteps={setExpandedSteps}
                        liveEndRef={liveEndRef}
                      />
                    ) : isInvestigating ? (
                      <LiveInvestigationTimeline
                        steps={[]}
                        isLive={true}
                        expandedSteps={expandedSteps}
                        setExpandedSteps={setExpandedSteps}
                        liveEndRef={liveEndRef}
                      />
                    ) : (
                      <div className="ai-chat-window">
                        <div className="ai-chat-header">
                          <div className="ai-avatar">🤖</div>
                          <div style={{ flex: 1 }}>
                            <div style={{ fontSize: '0.72rem', fontWeight: 700, color: 'var(--text-primary)' }}>FraudShield AI Agent</div>
                            <div style={{ fontSize: '0.6rem', color: 'var(--text-muted)' }}>Ready to investigate</div>
                          </div>
                          <span className="model-badge">Gemini 2.5</span>
                        </div>
                        <div style={{ padding: '1.25rem', textAlign: 'center' }}>
                          <div style={{ fontSize: '1.5rem', marginBottom: '0.4rem', opacity: 0.4 }}>🔍</div>
                          <p style={{ fontSize: '0.75rem', color: 'var(--text-muted)', margin: 0, lineHeight: 1.55 }}>
                            Click <strong style={{ color: 'var(--text-secondary)' }}>✦ Investigate</strong> to watch the AI agent reason through the evidence step by step — live.
                          </p>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* ── 5. Verdict ── */}
                  {report && (
                    <div style={{ marginBottom: '1rem', border: '1px solid rgba(239,68,68,0.25)', borderRadius: 8, overflow: 'hidden' }}>
                      <div style={{ background: 'rgba(239,68,68,0.08)', padding: '0.6rem 0.9rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <span style={{ fontSize: '0.65rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: '#f87171' }}>📋 Investigation Verdict</span>
                        <div style={{ display: 'flex', gap: '0.4rem' }}>
                          <span style={{ fontSize: '0.65rem', padding: '0.15rem 0.4rem', borderRadius: 4, background: report.evidenceStrength === 'HIGH' ? '#ef4444' : report.evidenceStrength === 'MEDIUM' ? '#f59e0b' : '#3b82f6', color: 'white', fontWeight: 700 }}>
                            {report.evidenceStrength} evidence
                          </span>
                          <span style={{ fontSize: '0.65rem', padding: '0.15rem 0.4rem', borderRadius: 4, border: '1px solid var(--border-color)', color: 'var(--text-secondary)', fontWeight: 600 }}>
                            {(report.recommendedAction || '').replace(/_/g, ' ')}
                          </span>
                        </div>
                      </div>
                      <div style={{ padding: '0.75rem 0.9rem', display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                        <p style={{ fontSize: '0.78rem', color: 'var(--text-primary)', lineHeight: 1.5, margin: 0 }}>{report.investigationSummary}</p>
                        {report.customerMessage && (
                          <div style={{ background: 'rgba(34,197,94,0.05)', border: '1px dashed rgba(34,197,94,0.25)', borderRadius: 5, padding: '0.5rem 0.7rem' }}>
                            <span style={{ fontSize: '0.6rem', fontWeight: 700, textTransform: 'uppercase', color: '#22c55e', display: 'block', marginBottom: '0.2rem' }}>📱 Customer SMS</span>
                            <span style={{ fontSize: '0.73rem', color: 'var(--text-primary)', fontStyle: 'italic' }}>"{report.customerMessage}"</span>
                          </div>
                        )}
                      </div>
                    </div>
                  )}

                  {/* ── 6. Actions ── */}
                  {selectedCase.status === 'OPEN' && (
                    <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '0.75rem' }}>
                      <button className="btn btn-secondary" style={{ flex: 1 }} onClick={() => updateCaseStatus(selectedCase.id, 'CLOSED')}>Dismiss — Not Fraud</button>
                      <button className="btn btn-danger" style={{ flex: 1 }} onClick={() => updateCaseStatus(selectedCase.id, 'ACCOUNT_FROZEN')}>🔒 Freeze Account</button>
                    </div>
                  )}

                  {/* ── 7. Analyst Copilot (collapsible) ── */}
                  <div style={{ border: '1px solid var(--border-color)', borderRadius: 8, overflow: 'hidden' }}>
                    <button
                      style={{ width: '100%', background: 'rgba(255,255,255,0.02)', border: 'none', padding: '0.6rem 0.9rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer', color: 'var(--text-secondary)', fontSize: '0.75rem', fontWeight: 600 }}
                      onClick={() => setChatExpanded(p => !p)}
                    >
                      <span>💬 Ask the AI Analyst</span>
                      <span>{chatExpanded ? '▲' : '▼'}</span>
                    </button>

                    {chatExpanded && (
                      <div className="chat-container" style={{ borderTop: '1px solid var(--border-color)' }}>
                        <div className="chat-messages">
                          <div className="chat-msg system">
                            I'm your fraud investigation assistant. Ask me anything about case {selectedCase.transactionId} — I can explain the anomalies, check transaction history, or help you decide whether to freeze the account.
                          </div>
                          {manualChatHistory.map((msg, i) => (
                            <div key={i} className={`chat-msg ${msg.role}`}>{msg.content}</div>
                          ))}
                          {chatLoading && <div className="chat-msg loading">Analyzing<span className="thinking-ellipsis" /></div>}
                          <div ref={chatEndRef} />
                        </div>
                        <div className="chat-input-row">
                          <input
                            type="text"
                            placeholder="e.g. 'Why was this flagged?' or 'Freeze the account'"
                            value={chatInput}
                            onChange={e => setChatInput(e.target.value)}
                            onKeyDown={e => { if (e.key === 'Enter') sendChatMessage(); }}
                            disabled={chatLoading}
                          />
                          <button onClick={sendChatMessage} disabled={!chatInput.trim() || chatLoading}>
                            {chatLoading ? '…' : 'Send'}
                          </button>
                        </div>
                      </div>
                    )}
                  </div>

                </div>
              );
            })()}
          </section>
        </main>
      ) : (
        /* ── Transactions Page ── */
        <main className="dashboard-grid" style={{ gridTemplateColumns: selectedTxn ? '1fr 360px' : '1fr' }}>
          <section className="column">
            <div className="column-header">
              <h2 className="column-title">Live Transaction Feed</h2>
              <span className="stat-bubble">{totalCount.toLocaleString()} total</span>
            </div>
            <div className="column-content" onScroll={handleScroll}>
              {transactions.length === 0 ? (
                <div style={{ textAlign: 'center', color: 'var(--text-muted)', marginTop: '4rem', fontSize: '0.9rem' }}>
                  No transactions yet. Click "Stream Feed" to start.
                </div>
              ) : (
                <div className="table-container">
                  <table>
                    <thead>
                      <tr>
                        <th>Time</th><th>Txn ID</th><th>Account</th><th>Amount</th>
                        <th>Location</th><th>Device</th><th>Category</th><th>Flags</th>
                      </tr>
                    </thead>
                    <tbody>
                      {transactions.slice(0, visibleCount).map(t => {
                        const flags = getFlagDetails(t);
                        return (
                          <tr
                            key={t.transactionId}
                            className={`${t.isFraud ? 'fraud' : ''} ${selectedTxn?.transactionId === t.transactionId ? 'active' : ''}`}
                            onClick={async () => {
                              setSelectedTxn(t);
                              const mc = cases.find(c => c.transactionId === t.transactionId);
                              if (mc) { setSelectedCase(mc); setSelectedUser(await fetchUser(mc.accountId)); }
                              else { setSelectedCase(null); setSelectedUser(await fetchUser(t.senderAccount)); }
                            }}
                          >
                            <td style={{ fontSize: '0.72rem', color: 'var(--text-secondary)' }}>{t.timestamp.split('T')[1]?.substring(0, 8) || t.timestamp}</td>
                            <td style={{ fontWeight: 600 }}>{t.transactionId}</td>
                            <td style={{ fontFamily: 'var(--font-mono)' }}>{t.senderAccount}</td>
                            <td style={{ fontWeight: 700, color: t.isFraud ? 'var(--color-danger)' : 'inherit' }}>${t.amount.toFixed(2)}</td>
                            <td>{t.location}</td>
                            <td style={{ textTransform: 'capitalize' }}>{t.deviceUsed}</td>
                            <td style={{ color: 'var(--text-secondary)' }}>{t.merchantCategory}</td>
                            <td>
                              <span className={`flag-pill ${flags.count >= 2 ? 'alert' : flags.count === 1 ? 'warn' : 'zero'}`}>
                                {flags.count} {flags.count === 1 ? 'Flag' : 'Flags'}
                              </span>
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

          {selectedTxn && (
            <section className="column" style={{ background: '#0a0e1a', borderLeft: '1px solid var(--border-color)' }}>
              <div className="column-header" style={{ justifyContent: 'space-between' }}>
                <h2 className="column-title">Transaction Detail</h2>
                <button className="btn btn-secondary" style={{ padding: '0.2rem 0.5rem', fontSize: '0.65rem' }} onClick={() => { setSelectedTxn(null); setSelectedCase(null); }}>✕ Close</button>
              </div>
              <div className="column-content" style={{ padding: '1rem' }}>
                <h3 style={{ fontSize: '1rem', fontWeight: 700, marginBottom: '0.25rem' }}>{selectedTxn.transactionId}</h3>
                <p style={{ fontSize: '0.72rem', color: 'var(--text-secondary)', marginBottom: '1rem' }}>{selectedTxn.transactionType} · {selectedTxn.merchantCategory}</p>

                {selectedUser ? (
                  <>
                    <div style={{ fontSize: '0.65rem', fontWeight: 700, textTransform: 'uppercase', color: 'var(--text-secondary)', marginBottom: '0.5rem' }}>Baseline Check</div>
                    {[
                      { label: 'Location', current: selectedTxn.location, baseline: selectedUser.frequentLocations.join(', '), anomaly: isLocationMismatch },
                      { label: 'Device', current: selectedTxn.deviceUsed, baseline: selectedUser.frequentDevices.join(', '), anomaly: isDeviceMismatch },
                      { label: 'Amount', current: `$${selectedTxn.amount.toFixed(2)}`, baseline: `Avg $${selectedUser.averageTransactionValue.toFixed(0)}`, anomaly: isAmountAnomaly },
                    ].map(({ label, current, baseline, anomaly }) => (
                      <div key={label} className={`compare-box ${anomaly ? 'mismatch' : ''}`} style={{ marginBottom: '0.4rem' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.65rem', fontWeight: 700, marginBottom: '0.15rem' }}>
                          <span>{label}</span>
                          <span style={{ color: anomaly ? '#ef4444' : '#22c55e' }}>{anomaly ? '⚠ Unusual' : '✓ Normal'}</span>
                        </div>
                        <div style={{ fontSize: '0.78rem', fontWeight: 600 }}>{current}</div>
                        <div style={{ fontSize: '0.62rem', color: 'var(--text-muted)' }}>Baseline: {baseline}</div>
                      </div>
                    ))}
                  </>
                ) : (
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', padding: '0.75rem', background: 'rgba(255,255,255,0.02)', borderRadius: 6, border: '1px solid var(--border-color)', lineHeight: 1.5 }}>
                    <span style={{ display: 'block', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '0.2rem' }}>📊 Baseline not yet established</span>
                    Account {selectedTxn.senderAccount} hasn't built a behavioral profile yet — more transaction history needed.
                  </div>
                )}

                {selectedCase && (
                  <div style={{ marginTop: '1rem' }}>
                    <span className="badge badge-open" style={{ display: 'inline-block', marginBottom: '0.4rem' }}>🚨 Linked Alert</span>
                    <div className="ai-reasoning">
                      <p style={{ margin: 0, lineHeight: 1.4 }}>{selectedCase.aiReasoning}</p>
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
