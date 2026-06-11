import httpx
import os
import pymongo
from mcp.server.fastmcp import FastMCP

# Initialize FastMCP Server
mcp = FastMCP("FraudShield")

# Database client — prefers MongoDB Atlas URI, falls back to local Docker
MONGO_URI = os.environ.get(
    "MONGODB_ATLAS_URI",
    os.environ.get("MONGODB_URI", "mongodb://admin:password@localhost:27017/")
)
mongo_client = pymongo.MongoClient(MONGO_URI)
db = mongo_client["fraudshield"]

API_BASE = os.environ.get("API_BASE", "http://localhost:8080/api")

@mcp.tool()
async def get_open_cases() -> str:
    """Retrieve all active (OPEN) fraud cases from the system."""
    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(f"{API_BASE}/cases")
            if response.status_code == 200:
                cases = response.json()
                open_cases = [c for c in cases if c.get("status") == "OPEN"]
                if not open_cases:
                    return "No open cases found."
                
                result = "Active Fraud Cases:\n"
                for c in open_cases:
                    result += f"- Case ID: {c['id']} | Txn: {c['transactionId']} | Account: {c['accountId']} | Risk: {c['riskScore']*100:.0f}% | Reasoning: {c['aiReasoning']}\n"
                return result
            else:
                return f"Error fetching cases: {response.text}"
        except Exception as e:
            return f"Error connecting to backend: {str(e)}"

@mcp.tool()
async def get_user_baseline(account_id: str) -> str:
    """Retrieve the behavioral baseline profile for a cardholder by account ID."""
    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(f"{API_BASE}/users/{account_id}")
            if response.status_code == 200:
                user = response.json()
                return (
                    f"User Profile for {user['name']} (Account: {user['accountId']}):\n"
                    f"  - Frequent Locations: {', '.join(user['frequentLocations'])}\n"
                    f"  - Frequent Devices: {', '.join(user['frequentDevices'])}\n"
                    f"  - Average Transaction Value: ${user['averageTransactionValue']:.2f}\n"
                )
            else:
                return f"User not found for account ID {account_id}"
        except Exception as e:
            return f"Error connecting to backend: {str(e)}"

@mcp.tool()
async def get_case_transactions(account_id: str) -> str:
    """Retrieve recent transaction history for a cardholder to analyze transaction patterns."""
    # Query directly from MongoDB to showcase direct database interaction
    try:
        txns = list(db["transactions"].find({"senderAccount": account_id}).sort("timestamp", -1).limit(10))
        if not txns:
            return f"No transaction history found for account {account_id}."
        
        result = f"Recent Transactions for Account {account_id}:\n"
        for t in txns:
            is_fraud_flag = " [SUSPECTED FRAUD]" if t.get("isFraud") else ""
            result += f"- {t['transactionId']} | {t['timestamp']} | ${t['amount']:.2f} | Type: {t['transactionType']} | Loc: {t['location']} | Dev: {t['deviceUsed']}{is_fraud_flag}\n"
        return result
    except Exception as e:
        return f"Error reading from MongoDB: {str(e)}"

@mcp.tool()
async def update_case_status(case_id: str, status: str) -> str:
    """Update the status of a fraud case (e.g. status='ACCOUNT_FROZEN' to freeze account or status='CLOSED' to dismiss)."""
    # Use API call so that the backend updates DB and broadcasts WebSocket events to the React frontend
    async with httpx.AsyncClient() as client:
        try:
            response = await client.put(f"{API_BASE}/cases/{case_id}/status?status={status}")
            if response.status_code == 200:
                c = response.json()
                return f"Success: Case {case_id} status updated to {c['status']} for Account {c['accountId']}."
            else:
                return f"Error updating case status: {response.text}"
        except Exception as e:
            return f"Error connecting to backend: {str(e)}"

@mcp.tool()
async def get_receiver_profile(receiver_account_id: str) -> str:
    """Retrieve transaction history for a receiver account to check for money mule patterns."""
    try:
        txns = list(db["transactions"].find({"receiverAccount": receiver_account_id}).sort("timestamp", -1).limit(10))
        if not txns:
            return f"No incoming transaction history found for receiver account {receiver_account_id}."
        
        result = f"Recent Incoming Transactions for Receiver Account {receiver_account_id}:\n"
        for t in txns:
            result += f"- {t['transactionId']} | {t['timestamp']} | ${t['amount']:.2f} | Sender: {t['senderAccount']} | Type: {t['transactionType']} | Loc: {t['location']}\n"
        return result
    except Exception as e:
        return f"Error reading from MongoDB: {str(e)}"

@mcp.tool()
async def submit_investigation_report(case_id: str, report_json: str) -> str:
    """Submit an autonomous agent investigation report for a specific fraud case."""
    async with httpx.AsyncClient() as client:
        try:
            response = await client.put(
                f"{API_BASE}/cases/{case_id}/report",
                content=report_json,
                headers={"Content-Type": "application/json"}
            )
            if response.status_code == 200:
                return f"Success: Investigation report attached to case {case_id}."
            else:
                return f"Error submitting report: {response.text}"
        except Exception as e:
            return f"Error connecting to backend: {str(e)}"

@mcp.tool()
async def get_fraud_network_stats() -> str:
    """
    Query MongoDB Atlas to produce a real-time fraud network intelligence report.
    Returns aggregate statistics: total cases, high-risk accounts, most targeted receivers,
    and velocity anomaly counts — powered by MongoDB Atlas aggregation pipeline.
    """
    try:
        # Aggregate fraud case stats
        total_cases = db["fraud_cases"].count_documents({})
        open_cases = db["fraud_cases"].count_documents({"status": "OPEN"})
        frozen_accounts = db["fraud_cases"].count_documents({"status": "ACCOUNT_FROZEN"})

        # Top receivers (potential money mules) — accounts receiving from 3+ unique senders
        pipeline = [
            {"$group": {"_id": "$receiverAccount", "uniqueSenders": {"$addToSet": "$senderAccount"}, "totalReceived": {"$sum": "$amount"}}},
            {"$project": {"receiverAccount": "$_id", "senderCount": {"$size": "$uniqueSenders"}, "totalReceived": 1}},
            {"$match": {"senderCount": {"$gte": 2}}},
            {"$sort": {"senderCount": -1}},
            {"$limit": 5}
        ]
        mule_candidates = list(db["transactions"].aggregate(pipeline))

        # High-value transaction anomalies in last 24 hours
        from datetime import datetime, timedelta
        since = datetime.utcnow() - timedelta(hours=24)
        recent_high_risk = db["transactions"].count_documents({
            "timestamp": {"$gte": since.isoformat()},
            "isFraud": True
        })

        result = f"FraudShield Network Intelligence Report (MongoDB Atlas):\n"
        result += f"  Total Cases: {total_cases} | Open: {open_cases} | Accounts Frozen: {frozen_accounts}\n"
        result += f"  High-Risk Transactions (last 24h): {recent_high_risk}\n"

        if mule_candidates:
            result += f"\nSuspected Money Mule Accounts (receiving from multiple senders):\n"
            for m in mule_candidates:
                result += f"  - Account {m['_id']}: {m['senderCount']} unique senders, ${m['totalReceived']:.2f} total received\n"
        else:
            result += "\nNo multi-sender receiver patterns detected currently.\n"

        return result
    except Exception as e:
        return f"Error running Atlas aggregation: {str(e)}"


if __name__ == "__main__":
    import sys
    # FastMCP uses standard stdio by default, running the server
    mcp.run("stdio")
