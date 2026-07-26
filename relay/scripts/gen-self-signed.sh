#!/usr/bin/env bash
# Generate a self-signed TLS cert for the VoxCrew Mac Mini relay (MVP).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${RELAY_CERT_DIR:-$ROOT/certs}"
mkdir -p "$OUT"
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$OUT/key.pem" \
  -out "$OUT/cert.pem" \
  -days 825 \
  -subj "/CN=voxcrew-relay/O=VoxCrew/C=FR"
echo "Wrote $OUT/cert.pem and $OUT/key.pem"
echo "SHA-256 fingerprint (put in deep link certSha256=):"
openssl x509 -in "$OUT/cert.pem" -noout -fingerprint -sha256 | sed 's/^.*=//' | tr -d ':' | tr 'A-F' 'a-f'
