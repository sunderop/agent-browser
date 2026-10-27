#!/usr/bin/env bash
set -e

echo "🚀 Generating AgentBrowser Update 5 files..."

# Create directory structure
mkdir -p tools
mkdir -p android
mkdir -p skills/web/browse

# ==========================================
# FILE 1: tools/browse (Bash Client)
# ==========================================
cat << 'EOF' > tools/browse
#!/usr/bin/env bash
set -e

HOST="127.0.0.1"
PORT="8765"
TOKEN_FILE="$HOME/.browse_token"

if [ ! -f "$TOKEN_FILE" ]; then
    echo "ERROR: Token file not found at $TOKEN_FILE"
    echo "Run: echo 'YOUR_TOKEN' > ~/.browse_token"
    exit 1
fi

TOKEN=$(cat "$TOKEN_FILE" | tr -d '[:space:]')

req() {
    local action=$1
    shift
    local params=""
    for arg in "$@"; do
        params="$params&$arg"
    done
    params="${params#&}"
    
    local response=$(curl -s -w "\n%{http_code}" -H "X-Token: $TOKEN" "http://$HOST:$PORT/?action=$action&$params")
    local http_code="${response##*$'\n'}"
    local body="${response%$'\n'*}"

    if [ "$http_code" = "403" ] && [[ "$body" == *"DENIED_BY_USER"* ]]; then
        echo "🛑 CRITICAL: Action DENIED BY USER. Halting."
        exit 1
    elif [ "$http_code" != "200" ]; then
        echo "ERROR: HTTP $http_code - $body"
        exit 1
    fi
    echo "$body"
}

do_click() {
    local index=$1
    local attempt=1
    while [ $attempt -le 2 ]; do
        local result=$(req "click" "index=$index")
        if [[ "$result" == *"Element not found"* ]] && [ $attempt -eq 1 ]; then
            echo "⚠️ Element $index not found. Refreshing DOM and retrying once..."
            req "elements" > /dev/null
            attempt=$((attempt + 1))
        else
            echo "$result"
            break
        fi
    done
}

case "$1" in
    open) req "open" "url=${2}" ;;
    url) req "url" ;;
    waitfor-load) req "waitfor-load" ;;
    waitfor-text) req "waitfor-text" "text=$(echo "$2" | jq -sRr @uri)" ;;
    elements) 
        if [ -n "$2" ]; then
            req "elements" "css=$(echo "$2" | jq -sRr @uri)&text=$(echo "$3" | jq -sRr @uri)"
        else
            req "elements"
        fi
        ;;
    click) do_click "$2" ;;
    type) req "type" "index=$2&text=$(echo "$3" | jq -sRr @uri)" ;;
    select) req "select" "index=$2&value=$(echo "$3" | jq -sRr @uri)" ;;
    submit) req "submit" "index=$2" ;;
    html) req "html" ;;
    meta) req "meta" ;;
    screenshot) 
        req "screenshot"
        echo "Saved to ~/shot.jpg"
        ;;
    back|forward|reload) req "$1" ;;
    tabs|newtab|tab|closetab|downloads|outline|inspect|press|scroll)
        cmd=$1
        shift
        req "$cmd" "$@"
        ;;
    run)
        if [ -z "$2" ] || [ ! -f "$2" ]; then
            echo "ERROR: File not found: $2"
            exit 1
        fi
        echo "▶️ Running workflow from $2..."
        line_num=1
        while IFS= read -r line || [ -n "$line" ]; do
            [[ -z "$line" || "$line" =~ ^# ]] && continue
            echo "[$line_num] $ $line"
            if ! bash "$0" $line; then
                echo "🛑 Workflow halted at line $line_num due to error or user denial."
                exit 1
            fi
            line_num=$((line_num + 1))
        done < "$2"
        echo "✅ Workflow completed successfully."
        ;;
    *)
        echo "Usage: browse <command> [args]"
        exit 1
        ;;
esac
EOF

# ==========================================
# FILE 2: android/AgentBrowserHandler.kt
# ==========================================
cat << 'EOF' > android/AgentBrowserHandler.kt
package com.sunderop.agentbrowser // VERIFY THIS MATCHES YOUR ACTUAL PACKAGE NAME

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object AgentBrowserHandler {
    val SENSITIVE_KEYWORDS = Regex("\\b(post|send|delete|buy|share|follow)\\b", RegexOption.IGNORE_CASE)

    fun handleRequest(action: String, params: Map<String, String>, webView: WebView): String {
        return when (action) {
            "waitfor-load" -> waitForLoad(webView)
            "waitfor-text" -> waitForText(webView, params["text"] ?: "")
            "click" -> handleSafeClick(webView, params["index"] ?: "0")
            else -> "UNKNOWN_COMMAND"
        }
    }

    private fun waitForLoad(webView: WebView): String {
        val future = CompletableFuture<String>()
        webView.evaluateJavascript("(function() { return document.readyState === 'complete' ? 'LOADED' : 'LOADING'; })();") { result ->
            future.complete(result.replace("\"", ""))
        }
        return try {
            val status = future.get(5, TimeUnit.SECONDS)
            if (status == "LOADED") "OK: Page loaded" else "WAIT: Page still loading"
        } catch (e: Exception) { "ERROR: Load timeout" }
    }

    private fun waitForText(webView: WebView, targetText: String): String {
        val future = CompletableFuture<Boolean>()
        val js = "(function() { return document.body.innerText.includes(\"$targetText\") ? \"FOUND\" : \"NOT_FOUND\"; })();"
        webView.evaluateJavascript(js) { result ->
            future.complete(result.replace("\"", "") == "FOUND")
        }
        return try {
            if (future.get(10, TimeUnit.SECONDS)) "OK: Text found" else "ERROR: Text not found within 10s"
        } catch (e: Exception) { "ERROR: Text wait timeout" }
    }

    private fun handleSafeClick(webView: WebView, indexStr: String): String {
        val index = indexStr.toIntOrNull() ?: return "ERROR: Invalid index"
        val future = CompletableFuture<String>()
        
        val checkJs = """
            (function() {
                var els = document.querySelectorAll('a, button, input[type="submit"], [role="button"]');
                var el = els[$index];
                if (!el) return "NOT_FOUND";
                return el.innerText + " " + (el.value || "") + " " + (el.getAttribute('aria-label') || "");
            })();
        """.trimIndent()

        webView.evaluateJavascript(checkJs) { result -> future.complete(result.replace("\"", "")) }

        return try {
            val elementInfo = future.get(3, TimeUnit.SECONDS)
            if (elementInfo == "NOT_FOUND") return "ERROR: Element not found"

            if (SENSITIVE_KEYWORDS.containsMatchIn(elementInfo)) {
                if (!showApprovalDialog(elementInfo, webView.context)) return "DENIED_BY_USER"
            }

            val clickJs = """
                (function() {
                    var els = document.querySelectorAll('a, button, input[type="submit"], [role="button"]');
                    if (els[$index]) { els[$index].click(); return "CLICKED"; }
                    return "NOT_FOUND";
                })();
            """.trimIndent()
            
            val clickFuture = CompletableFuture<String>()
            webView.evaluateJavascript(clickJs) { result -> clickFuture.complete(result.replace("\"", "")) }
            
            if (clickFuture.get(3, TimeUnit.SECONDS) == "CLICKED") "OK: Clicked" else "ERROR: Click failed"
        } catch (e: Exception) { "ERROR: Click execution failed" }
    }

    private fun showApprovalDialog(elementInfo: String, context: android.content.Context): Boolean {
        val future = CompletableFuture<Boolean>()
        val handler = Handler(Looper.getMainLooper())
        
        handler.post {
            var dialog: AlertDialog? = null
            dialog = AlertDialog.Builder(context)
                .setTitle("⚠️ Agent Action Requires Approval")
                .setMessage("The agent is trying to interact with: \"$elementInfo\".\n\nAllow this action? (Auto-denies in 60s)")
                .setPositiveButton("Allow") { _, _ -> future.complete(true) }
                .setNegativeButton("Deny") { _, _ -> future.complete(false) }
                .setCancelable(false)
                .create()
                
            dialog.show()
            handler.postDelayed({
                if (!future.isDone) {
                    dialog?.dismiss()
                    future.complete(false)
                }
            }, 60000)
        }
        return try { future.get() } catch (e: Exception) { false }
    }
}
EOF

# ==========================================
# FILE 3: test_skill.sh
# ==========================================
cat << 'EOF' > test_skill.sh
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
EOF

# ==========================================
# FILE 4: skills/web/browse/SKILL.md
# ==========================================
cat << 'EOF' > skills/web/browse/SKILL.md
# Browser Automation Skill (`browse`)

You control a real Android WebView via the `browse` CLI tool. 
The browser runs on the user's phone. You MUST follow all safety and operational rules.

## 🔒 CRITICAL SAFETY RULES
1. **HTTPS ONLY**: Never use `http://`. The WebView blocks plain HTTP. Always use `https://`.
2. **Human Approval**: Actions involving "post", "send", "delete", "buy", "share", or "follow" will trigger a 60-second Allow/Deny prompt on the user's phone. 
   - If the output says "denied by user" or "DENIED_BY_USER", you MUST STOP immediately and inform the user. Do not retry.
3. **Token Secrecy**: Never print, log, or echo the `X-Token` or `~/.browse_token`.
4. **One Agent**: Do not run multiple concurrent `browse` sessions.

## ⚠️ KNOWN LIMITS & BEST PRACTICES
- **Stale Elements**: Element numbers become INVALID after any page change, click, or navigation. You MUST run `browse elements` again before every `click` or `type`.
- **Loading States**: `browse elements` may return empty if the page is loading. Always run `browse waitfor-load` after navigation before checking elements.
- **No Iframes**: You cannot see or interact with elements inside iframes.
- **Background Pauses**: Keep the app visible on the phone screen. Screenshots may be blank if the app is hidden.

## 📚 COMMAND REFERENCE
- `browse open <https://url>` : Load a URL.
- `browse waitfor-load` : Wait for page to finish loading.
- `browse waitfor-text "<text>"` : Wait up to 10s for text to appear.
- `browse elements` : Get numbered list of clickable/typeable items. 
- `browse click <n>` : Click element #n. (Triggers approval prompt for sensitive actions).
- `browse type <n> "<text>"` : Type text into element #n.
- `browse select <n> "<option>"` : Select dropdown option.
- `browse run <file>` : Execute a sequence of commands from a file. Halts on "denied by user".

## 🔄 OPTIMAL WORKFLOW
1. `browse open https://example.com`
2. `browse waitfor-load`
3. `browse elements` (Find the target number, e.g., "5")
4. `browse click 5`
5. `browse waitfor-load` (Page changed, numbers are now stale!)
6. `browse elements` (Find the new target number)
EOF

# Make scripts executable
chmod +x tools/browse test_skill.sh

echo "✅ All files generated successfully in ~/agent-browser-update5/"
echo "Next steps:"
echo "1. Copy them to your repo: cp -r ~/agent-browser-update5/* ~/agent-browser/"
echo "2. Install jq: pkg install jq -y"
echo "3. Commit and push: cd ~/agent-browser && git add . && git commit -m 'Update 5' && git push"
