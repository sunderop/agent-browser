package com.agentbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONTokener

const val PORT = 8765

class MainActivity : Activity() {
    lateinit var web: WebView
    @Volatile private var loadLatch: CountDownLatch? = null
    private var server: BrowserServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val token = prefs.getString("token", null)
            ?: UUID.randomUUID().toString().replace("-", "").also {
                prefs.edit().putString("token", it).apply()
            }

        val info = TextView(this).apply {
            text = "Server: 127.0.0.1:$PORT\nToken (tap to copy):\n$token"
            setPadding(24, 24, 24, 12)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("token", token))
                Toast.makeText(this@MainActivity, "Token copied", Toast.LENGTH_SHORT).show()
            }
        }

        val urlBox = EditText(this).apply {
            hint = "https://"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val goBtn = Button(this).apply { text = "Go" }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(goBtn)
        }

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Drop the "wv" marker so sign-in pages treat this like a normal mobile browser
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loadLatch?.countDown()
            }
        }

        val go = {
            var u = urlBox.text.toString().trim()
            if (u.isNotEmpty()) {
                if (!u.startsWith("http")) u = "https://$u"
                web.loadUrl(u)
            }
        }
        goBtn.setOnClickListener { go() }
        urlBox.setOnEditorActionListener { _, _, _ -> go(); true }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(bar)
            addView(web, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        server = BrowserServer(this, token).also {
            it.start(5000, false)
        }
    }

    fun openUrl(url: String): Boolean {
        val latch = CountDownLatch(1)
        loadLatch = latch
        runOnUiThread { web.loadUrl(url) }
        return latch.await(30, TimeUnit.SECONDS)
    }

    fun js(script: String): String {
        val latch = CountDownLatch(1)
        var out = "timeout"
        runOnUiThread {
            web.evaluateJavascript(script) { r ->
                out = try {
                    JSONTokener(r ?: "null").nextValue().toString()
                } catch (e: Exception) {
                    r ?: ""
                }
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
        return out
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }
}
