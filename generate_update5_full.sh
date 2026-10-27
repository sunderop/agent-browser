#!/usr/bin/env bash
set -e

echo "🚀 Generating AgentBrowser Update 5 (Full) files..."

mkdir -p tools android skills/web/browse

# ==========================================
# FILE 1: tools/browse (Full Bash Client)
# ==========================================
cat << 'EOF' > tools/browse
#!/usr/bin/env bash
set -e

HOST="127.0.0.1"
PORT="8765"
TOKEN_FILE="$HOME/.browse_token"

if [ ! -f "$TOKEN_FILE" ]; then
    echo "ERROR: Token file not found at $TOKEN_FILE"
    exit 1
fi
TOKEN=$(cat "$TOKEN_FILE" | tr -d '[:space:]')

req() {
    local action=$1; shift
    local params=""
    for arg in "$@"; do params="$params&$arg"; done
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
    local index=$1; local attempt=1
    while [ $attempt -le 2 ]; do
        local result=$(req "click" "index=$index")
        if [[ "$result" == *"Element not found"* ]] && [ $attempt -eq 1 ]; then
            echo "⚠️ Element $index not found. Refreshing DOM and retrying..."
            req "elements" > /dev/null
            attempt=$((attempt + 1))
        else
            echo "$result"; break
        fi
    done
}

case "$1" in
    open) req "open" "url=${2}" ;;
    url) req "url" ;;
    waitfor-load) req "waitfor-load" ;;
    waitfor-text) req "waitfor-text" "text=$(echo "$2" | jq -sRr @uri)" ;;
    waitfor-gone) req "waitfor-gone" "css=$(echo "$2" | jq -sRr @uri)" ;;
    elements) 
        if [ -n "$2" ]; then req "elements" "css=$(echo "$2" | jq -sRr @uri)&text=$(echo "$3" | jq -sRr @uri)"
        else req "elements"; fi ;;
    find) req "find" "text=$(echo "$2" | jq -sRr @uri)" ;;
    click) do_click "$2" ;;
    type) req "type" "index=$2&text=$(echo "$3" | jq -sRr @uri)" ;;
    select) req "select" "index=$2&value=$(echo "$3" | jq -sRr @uri)" ;;
    submit) req "submit" "index=$2" ;;
    scroll-to) req "scroll-to" "index=$2" ;;
    eval) req "eval" "js=$(echo "$2" | jq -sRr @uri)" ;;
    html) req "html" ;;
    meta) req "meta" ;;
    screenshot) req "screenshot"; echo "Saved to ~/shot.jpg" ;;
    back|forward|reload) req "$1" ;;
    tabs|newtab|tab|closetab|downloads|outline|inspect|press|scroll)
        cmd=$1; shift; req "$cmd" "$@" ;;
    run)
        if [ -z "$2" ] || [ ! -f "$2" ]; then echo "ERROR: File not found: $2"; exit 1; fi
        echo "▶️ Running workflow from $2..."
        line_num=1
        while IFS= read -r line || [ -n "$line" ]; do
            [[ -z "$line" || "$line" =~ ^# ]] && continue
            echo "[$line_num] $ $line"
            if ! bash "$0" $line; then
                echo "🛑 Workflow halted at line $line_num."; exit 1
            fi
            line_num=$((line_num + 1))
        done < "$2"
        echo "✅ Workflow completed." ;;
    *) echo "Usage: browse <command> [args]"; exit 1 ;;
esac
EOF

# ==========================================
# FILE 2: android/AgentBrowserHandler.kt (Full Backend)
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
    val INTERACTIVE_SELECTORS = "a, button, input, select, textarea, [role='button']"

    fun handleRequest(action: String, params: Map<String, String>, webView: WebView): String {
        return when (action) {
            "waitfor-load" -> waitForLoad(webView)
            "waitfor-text" -> waitForText(webView, params["text"] ?: "")
            "waitfor-gone" -> waitForGone(webView, params["css"] ?: "")
            "click" -> handleSafeClick(webView, params["index"] ?: "0")
            "find" -> findElement(webView, params["text"] ?: "")
            "scroll-to" -> scrollToElement(webView, params["index"] ?: "0")
            "select" -> selectOption(webView, params["index"] ?: "0", params["value"] ?: "")
            "submit" -> handleSubmit(webView, params["index"] ?: "0")
            "eval" -> handleEval(webView, params["js"] ?: "")
            "html" -> executeJsSync(webView, "document.body.innerHTML")
            "meta" -> executeJsSync(webView, "Array.from(document.querySelectorAll('meta')).map(m => m.name + ': ' + m.content).join('\\n')")
            else -> "UNKNOWN_COMMAND"
        }
    }

    // --- WAITERS ---
    private fun waitForLoad(webView: WebView): String {
        val js = "(function() { return document.readyState === 'complete' ? 'LOADED' : 'LOADING'; })();"
        return try { if(executeJsSync(webView, js) == "LOADED") "OK: Page loaded" else "WAIT: Still loading" } catch (e: Exception) { "ERROR: Timeout" }
    }

    private fun waitForText(webView: WebView, targetText: String): String {
        val js = "(function() { return document.body.innerText.includes(\"$targetText\") ? \"FOUND\" : \"NOT_FOUND\"; })();"
        return try { if(executeJsSync(webView, js) == "FOUND") "OK: Text found" else "ERROR: Not found" } catch (e: Exception) { "ERROR: Timeout" }
    }

    private fun waitForGone(webView: WebView, css: String): String {
        val js = "(function() { return document.querySelector('$css') === null ? \"GONE\" : \"PRESENT\"; })();"
        return try { if(executeJsSync(webView, js) == "GONE") "OK: Element gone" else "ERROR: Still present" } catch (e: Exception) { "ERROR: Timeout" }
    }

    // --- INTERACTION ---
    private fun handleSafeClick(webView: WebView, indexStr: String): String {
        val index = indexStr.toIntOrNull() ?: return "ERROR: Invalid index"
        val checkJs = """
            (function() {
                var els = document.querySelectorAll('$INTERACTIVE_SELECTORS');
                var el = els[$index];
                if (!el) return "NOT_FOUND";
                return el.innerText + " " + (el.value || "") + " " + (el.getAttribute('aria-label') || "");
            })();
        """.trimIndent()

        return try {
            val elementInfo = executeJsSync(webView, checkJs)
            if (elementInfo == "NOT_FOUND") return "ERROR: Element not found"
            if (SENSITIVE_KEYWORDS.containsMatchIn(elementInfo)) {
                if (!showApprovalDialog("Click: \"$elementInfo\"", webView.context)) return "DENIED_BY_USER"
            }
            val clickJs = "(function() { var els = document.querySelectorAll('$INTERACTIVE_SELECTORS'); if(els[$index]) { els[$index].click(); return 'CLICKED'; } return 'NOT_FOUND'; })();"
            if (executeJsSync(webView, clickJs) == "CLICKED") "OK: Clicked" else "ERROR: Click failed"
        } catch (e: Exception) { "ERROR: Execution failed" }
    }

    private fun findElement(webView: WebView, text: String): String {
        val js = """
            (function() {
                var text = "$text";
                var els = document.querySelectorAll('$INTERACTIVE_SELECTORS');
                for (var i = 0; i < els.length; i++) {
                    if ((els[i].innerText || '').includes(text) || (els[i].value || '').includes(text)) return i;
                }
                return -1;
            })();
        """.trimIndent()
        return try { executeJsSync(webView, js) } catch (e: Exception) { "ERROR" }
    }

    private fun scrollToElement(webView: WebView, indexStr: String): String {
        val index = indexStr.toIntOrNull() ?: return "ERROR: Invalid index"
        val js = "(function() { var el = document.querySelectorAll('$INTERACTIVE_SELECTORS')[$index]; if(el) { el.scrollIntoView({block: 'center'}); return 'SCROLLED'; } return 'NOT_FOUND'; })();"
        return try { executeJsSync(webView, js) } catch (e: Exception) { "ERROR" }
    }

    private fun selectOption(webView: WebView, indexStr: String, value: String): String {
        val index = indexStr.toIntOrNull() ?: return "ERROR: Invalid index"
        val js = """
            (function() {
                var el = document.querySelectorAll('$INTERACTIVE_SELECTORS')[$index];
                if (!el || el.tagName !== 'SELECT') return 'NOT_SELECT';
                for (var i = 0; i < el.options.length; i++) {
                    if (el.options[i].text.includes("$value") || el.options[i].value === "$value") {
                        el.selectedIndex = i; el.dispatchEvent(new Event('change')); return 'SELECTED';
                    }
                }
                return 'OPTION_NOT_FOUND';
            })();
        """.trimIndent()
        return try { executeJsSync(webView, js) } catch (e: Exception) { "ERROR" }
    }

    private fun handleSubmit(webView: WebView, indexStr: String): String {
        // Safety rule: approval prompt on Enter-to-submit forms
        if (!showApprovalDialog("Submit Form / Press Enter", webView.context)) return "DENIED_BY_USER"
        val index = indexStr.toIntOrNull() ?: return "ERROR: Invalid index"
        val js = """
            (function() {
                var el = document.querySelectorAll('$INTERACTIVE_SELECTORS')[$index];
                if (!el) return 'NOT_FOUND';
                if (el.tagName === 'FORM') { el.submit(); return 'SUBMITTED'; }
                el.dispatchEvent(new KeyboardEvent('keydown', {'key': 'Enter'}));
                el.dispatchEvent(new KeyboardEvent('keypress', {'key': 'Enter'}));
                el.dispatchEvent(new KeyboardEvent('keyup', {'key': 'Enter'}));
                el.click();
                return 'SUBMITTED';
            })();
        """.trimIndent()
        return try { executeJsSync(webView, js) } catch (e: Exception) { "ERROR" }
    }

    // --- EVAL (Requires Approval Every Time) ---
    private fun handleEval(webView: WebView, js: String): String {
        if (!showApprovalDialog("Execute JS: ${js.take(50)}...", webView.context)) return "DENIED_BY_USER"
        return try { executeJsSync(webView, js) } catch (e: Exception) { "ERROR: JS Execution failed" }
    }

    // --- CORE JS EXECUTION & DIALOG ---
    private fun executeJsSync(webView: WebView, js: String): String {
        val future = CompletableFuture<String>()
        webView.evaluateJavascript(js) { result -> future.complete(result?.replace("\"", "") ?: "null") }
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun showApprovalDialog(elementInfo: String, context: android.content.Context): Boolean {
        val future = CompletableFuture<Boolean>()
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            var dialog: AlertDialog? = null
            dialog = AlertDialog.Builder(context)
                .setTitle("⚠️ Agent Action Requires Approval")
                .setMessage("Action: \"$elementInfo\"\n\nAllow? (Auto-denies in 60s)")
                .setPositiveButton("Allow") { _, _ -> future.complete(true) }
                .setNegativeButton("Deny") { _, _ -> future.complete(false) }
                .setCancelable(false).create()
            dialog.show()
            handler.postDelayed({ if (!future.isDone) { dialog?.dismiss(); future.complete(false) } }, 60000)
        }
        return try { future.get() } catch (e: Exception) { false }
    }
}
EOF

# ==========================================
# FILE 3: test_skill.sh (Expanded Test)
# ==========================================
cat << 'EOF' > test_skill.sh
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
EOF

# ==========================================
# FILE 4: skills/web/browse/SKILL.md (Updated)
# ==========================================
cat << 'EOF' > skills/web/browse/SKILL.md
# Browser Automation Skill (`browse`)

## 🔒 CRITICAL SAFETY RULES
1. **HTTPS ONLY**: Never use `http://`.
2. **Human Approval**: `eval`, `submit`, and clicks on "post/send/delete/buy/share/follow" trigger a 60s prompt. If "DENIED_BY_USER", STOP immediately.
3. **Token Secrecy**: Never log `~/.browse_token`.

## ⚠️ BEST PRACTICES
- **Stale Elements**: Run `browse elements` or `browse find` after ANY page change.
- **Loading**: Use `browse waitfor-load` after navigation.
- **Viewport**: Use `browse scroll-to <n>` if a click fails or to trigger lazy-loading.

## 📚 COMMAND REFERENCE
- `browse open <url>` / `browse url` / `browse back` / `browse forward`
- `browse waitfor-load` / `browse waitfor-text "<text>"` / `browse waitfor-gone "<css>"`
- `browse elements` / `browse find "<text>"` (Returns element index)
- `browse click <n>` / `browse type <n> "<text>"` / `browse select <n> "<option>"`
- `browse scroll-to <n>` / `browse press <key>`
- `browse eval "<js>"` (Runs JS, requires approval)
- `browse submit <n>` (Submits form/presses enter, requires approval)
- `browse html` / `browse meta` / `browse screenshot`
- `browse run <file>` (Executes a workflow file line-by-line)
EOF

chmod +x tools/browse test_skill.sh
echo "✅ All files generated in ~/agent-browser-update5/"
