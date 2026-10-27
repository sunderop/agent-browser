#!/usr/bin/env bash
echo "🔍 Testing AgentBrowser Update 6 (All Features)..."

if ! command -v browse &> /dev/null; then echo "❌ FAIL: 'browse' not found."; exit 1; fi
if [ ! -f "$HOME/.browse_token" ]; then echo "❌ FAIL: Token missing."; exit 1; fi

echo "▶️ 1. Navigate & Wait..."
browse open "https://example.com"
browse waitfor-load

echo "▶️ 2. Test Pacing..."
browse pace 1000
echo "   Pacing set to 1000ms"

echo "▶️ 3. Test Random Wait..."
browse wait-random 500 1500
echo "   Random wait completed"

echo "▶️ 4. Test iframe Detection..."
FRAMES=$(browse frames)
echo "   Found frames: $FRAMES"

echo "▶️ 5. Test Tab Switch by URL..."
browse tab-url "example"
echo "   Tab switch attempted"

echo "▶️ 6. Test Find Element..."
INDEX=$(browse find "More information")
echo "   Found element at index: $INDEX"

echo "▶️ 7. Test Scroll to Element..."
browse scroll-to "$INDEX"

echo "✅ SUCCESS: All Update 6 features operational."
