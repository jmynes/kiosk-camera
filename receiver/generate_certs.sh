#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="$SCRIPT_DIR/certs"
mkdir -p "$CERT_DIR"

# Detect LAN IP
IP=$(ip addr show | grep "inet " | grep -v 127.0.0.1 | awk '{print $2}' | cut -d/ -f1 | head -1)
echo "Using IP: $IP"

# Generate CA
openssl genrsa -out "$CERT_DIR/ca.key" 4096
openssl req -new -x509 -days 3650 -key "$CERT_DIR/ca.key" \
    -out "$CERT_DIR/ca.pem" \
    -subj "/CN=KioskCamera CA"

# Generate server key and CSR
openssl genrsa -out "$CERT_DIR/server.key" 4096
openssl req -new -key "$CERT_DIR/server.key" \
    -out "$CERT_DIR/server.csr" \
    -subj "/CN=$IP"

# Sign with SAN for the IP
cat > "$CERT_DIR/server_ext.cnf" <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
subjectAltName=IP:$IP
EOF

openssl x509 -req -in "$CERT_DIR/server.csr" \
    -CA "$CERT_DIR/ca.pem" -CAkey "$CERT_DIR/ca.key" \
    -CAcreateserial -days 3650 \
    -extfile "$CERT_DIR/server_ext.cnf" \
    -out "$CERT_DIR/server.pem"

# Extract SHA-256 pin for OkHttp CertificatePinner
PIN=$(openssl x509 -in "$CERT_DIR/server.pem" -pubkey -noout \
    | openssl pkey -pubin -outform der \
    | openssl dgst -sha256 -binary \
    | openssl enc -base64)

echo ""
echo "=== Certificates generated in $CERT_DIR ==="
echo "Server IP: $IP"
echo "CA cert:     $CERT_DIR/ca.pem"
echo "Server cert: $CERT_DIR/server.pem"
echo "Server key:  $CERT_DIR/server.key"
echo ""
echo "OkHttp certificate pin (SHA-256):"
echo "  sha256/$PIN"
echo ""
echo "Copy ca.pem to app/src/main/res/raw/ca_cert.pem for pinning"

# Copy CA cert to Android resources
RAW_DIR="$SCRIPT_DIR/../app/src/main/res/raw"
mkdir -p "$RAW_DIR"
cp "$CERT_DIR/ca.pem" "$RAW_DIR/ca_cert.pem"
echo "Copied CA cert to $RAW_DIR/ca_cert.pem"

# Write pin to a file for build script to read
echo "$PIN" > "$CERT_DIR/pin.txt"
echo "$IP" > "$CERT_DIR/ip.txt"
