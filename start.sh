#!/bin/sh
set -e

echo "========================================"
echo "Starting Tailscale..."
echo "========================================"

mkdir -p /var/run/tailscale
mkdir -p /var/lib/tailscale

tailscaled \
    --tun=userspace-networking \
    --state=/var/lib/tailscale/tailscaled.state \
    --socket=/var/run/tailscale/tailscaled.sock &

TAILSCALED_PID=$!

echo "Waiting for tailscaled socket..."

for i in $(seq 1 30); do
    if [ -S /var/run/tailscale/tailscaled.sock ]; then
        echo "tailscaled socket is ready"
        break
    fi

    if ! kill -0 "$TAILSCALED_PID" 2>/dev/null; then
        echo "ERROR: tailscaled process died"
        exit 1
    fi

    sleep 1
done

if [ ! -S /var/run/tailscale/tailscaled.sock ]; then
    echo "ERROR: tailscaled socket was not created"
    exit 1
fi

if [ -z "$TS_AUTHKEY" ]; then
    echo "ERROR: TS_AUTHKEY is not set"
    exit 1
fi

echo "========================================"
echo "Connecting to Tailscale..."
echo "========================================"

tailscale \
    --socket=/var/run/tailscale/tailscaled.sock \
    up \
    --auth-key="$TS_AUTHKEY" \
    --hostname="ktorservice-render"

echo "========================================"
echo "Tailscale connected"
echo "========================================"

tailscale \
    --socket=/var/run/tailscale/tailscaled.sock \
    status

echo "========================================"
echo "Starting Ktor..."
echo "========================================"

echo "JAVA VERSION:"
java -version

echo "CHECKING JAR..."

if [ ! -f build/libs/KtorService-all.jar ]; then
    echo "ERROR: JAR NOT FOUND"
    ls -lah build/libs
    exit 1
fi

ls -lh build/libs/KtorService-all.jar

echo "STARTING JAVA..."

exec java -jar build/libs/KtorService-all.jar