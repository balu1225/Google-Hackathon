# ── FraudShield Frontend ──
# Multi-stage build for React + Vite

FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .

# Build with production API URL (override at deploy time)
ARG VITE_API_BASE=http://localhost:8080/api
ARG VITE_WS_URL=ws://localhost:8080/ws/stream
RUN npm run build

FROM nginx:alpine
# Copy built assets
COPY --from=build /app/dist /usr/share/nginx/html

# Nginx config for SPA routing
RUN echo 'server { \
    listen 80; \
    location / { \
        root /usr/share/nginx/html; \
        index index.html; \
        try_files $uri $uri/ /index.html; \
    } \
}' > /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
