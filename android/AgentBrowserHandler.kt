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
