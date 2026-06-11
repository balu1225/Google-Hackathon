#!/bin/bash
# ── FraudShield Cloud Run Deployment Script ──
# Deploys backend and frontend to Google Cloud Run with MongoDB Atlas

set -e

# ── Configuration ──
PROJECT_ID=$(gcloud config get-value project)
REGION="us-central1"
BACKEND_IMAGE="gcr.io/$PROJECT_ID/fraudshield-backend"
FRONTEND_IMAGE="gcr.io/$PROJECT_ID/fraudshield-frontend"

echo "═══════════════════════════════════════════"
echo "  FraudShield Cloud Run Deployment"
echo "  Project: $PROJECT_ID"
echo "  Region:  $REGION"
echo "═══════════════════════════════════════════"

# ── Step 1: Enable required APIs ──
echo ""
echo "▶ Enabling required GCP APIs..."
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  containerregistry.googleapis.com \
  aiplatform.googleapis.com \
  --project="$PROJECT_ID"

# ── Step 2: Build & Push Backend ──
echo ""
echo "▶ Building backend Docker image..."
cd backend
gcloud builds submit --tag "$BACKEND_IMAGE" --timeout=600s
cd ..

# ── Step 3: Deploy Backend to Cloud Run ──
echo ""
echo "▶ Deploying backend to Cloud Run..."

# NOTE: You need to set MONGODB_URI to your MongoDB Atlas connection string
# Replace the placeholder below with your actual MongoDB Atlas URI
MONGODB_URI="${MONGODB_URI:-mongodb+srv://admin:password@cluster0.xxxxx.mongodb.net/fraudshield?retryWrites=true&w=majority}"

gcloud run deploy fraudshield-backend \
  --image "$BACKEND_IMAGE" \
  --platform managed \
  --region "$REGION" \
  --allow-unauthenticated \
  --port 8080 \
  --memory 1Gi \
  --cpu 2 \
  --min-instances 0 \
  --max-instances 3 \
  --set-env-vars "SPRING_DATA_MONGODB_URI=$MONGODB_URI,GCP_PROJECT_ID=$PROJECT_ID,GCP_REGION=$REGION" \
  --timeout=300s

# Get the backend URL
BACKEND_URL=$(gcloud run services describe fraudshield-backend --region "$REGION" --format="value(status.url)")
echo "✅ Backend deployed at: $BACKEND_URL"

# ── Step 4: Build & Push Frontend ──
echo ""
echo "▶ Building frontend Docker image..."
cd frontend
gcloud builds submit \
  --tag "$FRONTEND_IMAGE" \
  --timeout=600s
cd ..

# ── Step 5: Deploy Frontend to Cloud Run ──
echo ""
echo "▶ Deploying frontend to Cloud Run..."
gcloud run deploy fraudshield-frontend \
  --image "$FRONTEND_IMAGE" \
  --platform managed \
  --region "$REGION" \
  --allow-unauthenticated \
  --port 80 \
  --memory 256Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 2

FRONTEND_URL=$(gcloud run services describe fraudshield-frontend --region "$REGION" --format="value(status.url)")
echo "✅ Frontend deployed at: $FRONTEND_URL"

echo ""
echo "═══════════════════════════════════════════"
echo "  Deployment Complete!"
echo "  Frontend: $FRONTEND_URL"
echo "  Backend:  $BACKEND_URL"
echo "  API Docs: $BACKEND_URL/swagger-ui/index.html"
echo "═══════════════════════════════════════════"
echo ""
echo "⚠️  IMPORTANT: Update the frontend API_BASE and WS_URL"
echo "   in App.tsx to point to: $BACKEND_URL"
echo "   Then rebuild and redeploy the frontend."
