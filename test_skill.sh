#!/usr/bin/env bash
echo "🔍 Testing AgentBrowser Update 5 (Full)..."

if ! command -v browse &> /dev/null; then echo "❌ FAIL: 'browse' not found."; exit 1; fi
if [ ! -f "$HOME/.browse_token" ]; then echo "❌ FAIL: Token missing."; exit 1; fi

echo "▶️ 1. Navigate & Wait..."
browse open "https://example.com"
browse waitfor-load

echo "▶️ 2. Find element by text..."
INDEX=$(browse find "More information")
if [ "$INDEX" = "-1" ] || [ -z "$INDEX" ]; then echo "❌ FAIL: Could not find text."; exit 1; fi
echo "   Found 'More information' at index: $INDEX"

echo "▶️ 3. Scroll to element..."
browse scroll-to "$INDEX"

echo "▶️ 4. Get Meta tags..."
browse meta | head -n 3

echo "✅ SUCCESS: All Update 5 features operational."
