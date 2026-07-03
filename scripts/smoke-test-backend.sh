#!/usr/bin/env bash
# Smoke-test deployed signaling service (non-destructive).
set -euo pipefail

BASE_URL="${1:-}"

if [[ -z "$BASE_URL" ]]; then
  echo "Usage: $0 <cloud-run-base-url>" >&2
  exit 1
fi

echo "GET /health"
curl -sf "${BASE_URL}/health" | head -c 500
echo ""
echo "GET /ready"
curl -sf "${BASE_URL}/ready" | head -c 500
echo ""
echo "WebSocket: open ${BASE_URL/https/wss}/ws manually or via Android app"
