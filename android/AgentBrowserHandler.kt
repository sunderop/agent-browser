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
