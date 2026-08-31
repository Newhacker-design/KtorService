#!/bin/sh
set -e

TAILSCALE_SOCKET="/var/run/tailscale/tailscaled.sock"
TAILSCALE_STATE="/var/lib/tailscale/tailscaled.state"

DB_HOST="${DB_HOST:-100.76.246.38}"
DB_PORT="${DB_PORT:-5432}"

echo "========================================"
echo "Starting Tailscale..."
echo "========================================"

mkdir -p /var/run/tailscale
mkdir -p /var/lib/tailscale


# ==================================================
# Check auth key
# ==================================================

if [ -z "$TS_AUTHKEY" ]; then
    echo "ERROR: TS_AUTHKEY is not set"
    exit 1
fi


# ==================================================
# Start tailscaled
# ==================================================

echo "Starting tailscaled..."

tailscaled \
    --tun=userspace-networking \
    --state="$TAILSCALE_STATE" \
    --socket="$TAILSCALE_SOCKET" &

TAILSCALED_PID=$!


# ==================================================
# Wait for socket
# ==================================================

echo "Waiting for tailscaled socket..."

SOCKET_READY=false

for i in $(seq 1 30); do

    if [ -S "$TAILSCALE_SOCKET" ]; then
        echo "tailscaled socket is ready"
        SOCKET_READY=true
        break
    fi

    if ! kill -0 "$TAILSCALED_PID" 2>/dev/null; then
        echo "ERROR: tailscaled process died"
        exit 1
    fi

    sleep 1
done

if [ "$SOCKET_READY" != "true" ]; then
    echo "ERROR: tailscaled socket was not created"
    exit 1
fi


# ==================================================
# Connect Tailscale
# ==================================================

echo "========================================"
echo "Connecting to Tailscale..."
echo "========================================"

tailscale \
    --socket="$TAILSCALE_SOCKET" \
    up \
    --auth-key="$TS_AUTHKEY" \
    --hostname="ktorservice-render" \
    --accept-dns=false


# ==================================================
# Wait for Tailscale Running
# ==================================================

echo "========================================"
echo "Waiting for Tailscale..."
echo "========================================"

TS_RUNNING=false

for i in $(seq 1 30); do

    STATUS=$(
        tailscale \
            --socket="$TAILSCALE_SOCKET" \
            status 2>/dev/null || true
    )

    echo "$STATUS"

    if echo "$STATUS" | grep -q "ktorservice-render"; then
        TS_RUNNING=true
        echo "Tailscale is connected"
        break
    fi

    sleep 1
done

if [ "$TS_RUNNING" != "true" ]; then
    echo "ERROR: Tailscale did not connect"
    exit 1
fi


# ==================================================
# Show Tailscale status
# ==================================================

echo "========================================"
echo "Tailscale connected"
echo "========================================"

tailscale \
    --socket="$TAILSCALE_SOCKET" \
    status


# ==================================================
# Wait for PostgreSQL
# ==================================================

echo "========================================"
echo "Checking PostgreSQL..."
echo "========================================"

echo "DATABASE HOST = $DB_HOST"
echo "DATABASE PORT = $DB_PORT"

DB_READY=false

for i in $(seq 1 60); do

    if nc -z -w 2 "$DB_HOST" "$DB_PORT" 2>/dev/null; then
        echo "PostgreSQL is reachable!"
        DB_READY=true
        break
    fi

    echo "Waiting for PostgreSQL... attempt $i/60"

    sleep 2
done


if [ "$DB_READY" != "true" ]; then

    echo "========================================"
    echo "ERROR: PostgreSQL is NOT reachable"
    echo "========================================"

    echo "Testing Tailscale connection..."

    tailscale \
        --socket="$TAILSCALE_SOCKET" \
        ping "$DB_HOST" || true

    exit 1
fi


# ==================================================
# Start Ktor
# ==================================================

echo "========================================"
echo "Starting Ktor..."
echo "========================================"

echo "JAVA VERSION:"
java -version

echo "CHECKING JAR:"
ls -lh /app/build/libs/KtorService-all.jar

echo "STARTING JAVA..."

exec java \
    --enable-native-access=ALL-UNNAMED \
    -jar /app/build/libs/KtorService-all.jar
