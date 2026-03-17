#!/bin/bash

# Market Scout Launcher
# Works with Platypus to run as a native macOS app

# Find the directory where this script lives (inside the .app bundle)
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Market Scout lives next to the .app — adjust if you move it
APP_DIR="$HOME/Downloads/market-scout"

# Kill any existing instance on port 8080
lsof -ti:8080 | xargs kill -9 2>/dev/null

# Check Python is available
if ! command -v python3 &>/dev/null; then
    osascript -e 'display alert "Python 3 not found" message "Please install Python 3 from python.org" as critical'
    exit 1
fi

# Check dependencies
cd "$APP_DIR"
python3 -c "import flask, playwright" 2>/dev/null
if [ $? -ne 0 ]; then
    osascript -e 'display alert "Missing dependencies" message "Run setup.sh first to install required packages." as critical'
    exit 1
fi

# Start Flask server in background
python3 app.py &
SERVER_PID=$!

# Wait for server to be ready (poll /status)
echo "Starting Market Scout..."
for i in $(seq 1 30); do
    sleep 1
    STATUS=$(curl -s http://127.0.0.1:8080/status 2>/dev/null)
    if echo "$STATUS" | grep -q '"ready"'; then
        break
    fi
done

# Open in default browser
open http://127.0.0.1:8080

# Keep script running — when user quits the app, kill the server
echo "Market Scout is running."
echo "Quit the app to stop the server."

# Wait for server process
wait $SERVER_PID
