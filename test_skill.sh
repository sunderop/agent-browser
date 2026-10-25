#!/usr/bin/env bash
echo "🔍 Testing AgentBrowser Skill Integration..."

if ! command -v browse &> /dev/null; then
    echo "❌ FAIL: 'browse' command not found in PATH."
    exit 1
fi

if [ ! -f "$HOME/.browse_token" ]; then
    echo "❌ FAIL: ~/.browse_token not found."
    exit 1
fi

echo "✅ Prerequisites met."
echo "▶️ Step 1: Navigating to example.com..."
browse open "https://example.com"

echo "▶️ Step 2: Waiting for load..."
browse waitfor-load

echo "▶️ Step 3: Getting outline..."
browse outline

echo "▶️ Step 4: Getting elements..."
ELEMENTS=$(browse elements)

if [[ "$ELEMENTS" == *"ERROR"* ]] || [[ -z "$ELEMENTS" ]]; then
    echo "❌ FAIL: Could not retrieve elements. Is the Android app open and visible?"
    exit 1
fi

echo "✅ SUCCESS: AgentBrowser is fully operational."
echo "$ELEMENTS" | head -n 5
