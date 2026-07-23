#!/bin/bash
cd "$(dirname "$0")"
PID=$(cat .app.pid 2>/dev/null)
if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    echo "App already running (PID $PID)"
    exit 1
fi
mvn spring-boot:run -Dspring-boot.run.profiles=vastai -q > /dev/null 2>&1 &
echo $! > .app.pid
echo "Started app (PID $!)"
sleep 5
echo "Status: $(curl -s http://localhost:8080/api/models 2>/dev/null || echo 'not ready yet')"
