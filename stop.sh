#!/bin/bash
cd "$(dirname "$0")"
PID=$(cat .app.pid 2>/dev/null)
if [ -z "$PID" ]; then
    echo "No PID file found"
    exit 1
fi
if ! kill -0 "$PID" 2>/dev/null; then
    echo "App not running (stale PID $PID)"
    rm -f .app.pid
    exit 1
fi
echo "Stopping app (PID $PID)..."
kill "$PID"
for i in 1 2 3 4 5; do
    sleep 1
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "Stopped."
        rm -f .app.pid
        exit 0
    fi
    echo "Waiting..."
done
echo "Force killing..."
kill -9 "$PID"
rm -f .app.pid
