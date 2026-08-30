#!/bin/sh
set -e

echo "========================================"
echo "Starting Tailscale..."
echo "========================================"

mkdir -p /var/lib/tailscale
mkdir -p /var/run/tailscale

tailscaled \
  --state=/var/lib/tailscale/tailscaled.state \
  --socket=/var/run/tailscale/tailscaled.sock &

echo "Waiting for tailscaled..."

for i in $(seq 1 30); do
    if tailscale --socket=/var/run/tailscale/tailscaled.sock status >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

echo "Connecting to Tailscale..."

tailscale \
  --socket=/var/run/tailscale/tailscaled.sock \
  up \
  --auth-key="${TAILSCALE_AUTHKEY}" \
  --hostname="ktorservice-render"

echo "========================================"
echo "TAILSCALE STATUS"
echo "========================================"

tailscale \
  --socket=/var/run/tailscale/tailscaled.sock \
  status

echo "========================================"
echo "TAILSCALE IP"
echo "========================================"

tailscale \
  --socket=/var/run/tailscale/tailscaled.sock \
  ip

echo "========================================"
echo "Starting Ktor..."
echo "========================================"

exec java -jar build/libs/KtorService-all.jar