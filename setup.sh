#!/bin/bash
# Market Scout — One-time setup
echo ""
echo "═══════════════════════════════════"
echo "   MARKET SCOUT — Setup"
echo "═══════════════════════════════════"

pip3 install flask playwright playwright-stealth 2>/dev/null
python3 -m playwright install chromium

echo ""
echo "✅ Setup complete."
echo ""
echo "To run Market Scout:"
echo "   python3 app.py"
echo ""
echo "Then open: http://localhost:5000"
echo ""
