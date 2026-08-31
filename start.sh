#!/bin/sh
set -e

TAILSCALE_SOCKET="/var/run/tailscale/tailscaled.sock"
TAILSCALE_STATE="/var/lib/tailscale/tailscaled.state"
TAILSCALE_HOSTNAME="ktorservice-render"

echo "========================================"
echo "Starting Tailscale..."
echo "========================================"

mkdir -p /var/run/tailscale
mkdir -p /var/lib/tailscale

if [ -z "$TS_AUTHKEY" ]; then
    echo "ERROR: TS_AUTHKEY is not set"
    exit 1
fi

if [ -z "$TS_API_KEY" ]; then
    echo "ERROR: TS_API_KEY is not set"
    exit 1
fi

echo "========================================"
echo "Cleaning old Tailscale nodes..."
echo "========================================"

# Lấy danh sách tất cả devices trong tailnet
DEVICES_JSON=$(curl -fsS \
    -u "${TS_API_KEY}:" \
    "https://api.tailscale.com/api/v2/tailnet/-/devices")

if [ -z "$DEVICES_JSON" ]; then
    echo "WARNING: Could not get Tailscale device list"
else
    echo "$DEVICES_JSON" |
    python3 -c '
import sys
import json

data = json.load(sys.stdin)

for device in data.get("devices", []):
    name = device.get("name", "")
    device_id = device.get("id", "")

    if name.startswith("ktorservice-render"):
        print(device_id)
    ' |
    while read -r DEVICE_ID; do

        if [ -n "$DEVICE_ID" ]; then
            echo "Removing old device: $DEVICE_ID"

            curl -fsS -X DELETE \
                -u "${TS_API_KEY}:" \
                "https://api.tailscale.com/api/v2/device/${DEVICE_ID}" \
                || echo "WARNING: Failed to remove device ${DEVICE_ID}"

            sleep 1
        fi

    done
fi

echo "========================================"
echo "Starting tailscaled..."
echo "========================================"

tailscaled \
    --tun=userspace-networking \
    --state="$TAILSCALE_STATE" \
    --socket="$TAILSCALE_SOCKET" &

TAILSCALED_PID=$!

echo "Waiting for tailscaled socket..."

for i in $(seq 1 30); do

    if [ -S "$TAILSCALE_SOCKET" ]; then
        echo "tailscaled socket is ready"
        break
    fi

    if ! kill -0 "$TAILSCALED_PID" 2>/dev/null; then
        echo "ERROR: tailscaled process died"
        exit 1
    fi

    sleep 1
done

if [ ! -S "$TAILSCALE_SOCKET" ]; then
    echo "ERROR: tailscaled socket was not created"
    exit 1
fi

echo "========================================"
echo "Connecting to Tailscale..."
echo "========================================"

tailscale \
    --socket="$TAILSCALE_SOCKET" \
    up \
    --auth-key="$TS_AUTHKEY" \
    --hostname="$TAILSCALE_HOSTNAME" \
    --reset

echo "========================================"
echo "Tailscale connected"
echo "========================================"

tailscale \
    --socket="$TAILSCALE_SOCKET" \
    status

echo "========================================"
echo "Starting Ktor..."
echo "========================================"

echo "JAVA VERSION:"
java -version

echo "CHECKING JAR:"
ls -lh /app/build/libs/KtorService-all.jar

echo "STARTING JAVA..."

exec java -jar /app/build/libs/KtorService-all.jar