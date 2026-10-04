package com.agentbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONTokener

const val PORT = 8765

class MainActivity : Activity() {
    lateinit var web: WebView
    @Volatile private var loadLatch: CountDownLatch? = null
    private var server: BrowserServer? = null
    private val downloadLog = CopyOnWriteArrayList<Pair<Long, String>>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

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

        val backBtn = Button(this).apply { text = "Back"; setOnClickListener { if (web.canGoBack()) web.goBack() } }
        val fwdBtn = Button(this).apply { text = "Forward"; setOnClickListener { if (web.canGoForward()) web.goForward() } }
        val reloadBtn = Button(this).apply { text = "Reload"; setOnClickListener { web.reload() } }
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            addView(backBtn, lp())
            addView(fwdBtn, lp())
            addView(reloadBtn, lp())
        }

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
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (cookie != null) req.addRequestHeader("Cookie", cookie)
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val id = dm.enqueue(req)
                downloadLog.add(Pair(id, name))
                Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
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
            addView(navBar)
            addView(web, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        startForegroundService(Intent(this, KeepAliveService::class.java))

        server = BrowserServer(this, token).also {
            it.start(5000, false)
        }
    }

    // Phone's Back gesture/button goes to the previous page first
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    fun openUrl(url: String): Boolean {
        val latch = CountDownLatch(1)
        loadLatch = latch
        runOnUiThread { web.loadUrl(url) }
        return latch.await(30, TimeUnit.SECONDS)
    }

    // action: "back", "forward" or "reload"
    fun nav(action: String): String {
        val ran = CountDownLatch(1)
        val load = CountDownLatch(1)
        var msg = ""
        var navigating = false
        runOnUiThread {
            when (action) {
                "back" -> if (web.canGoBack()) {
                    loadLatch = load; navigating = true; web.goBack(); msg = "back"
                } else msg = "cannot go back (no earlier page)"
                "forward" -> if (web.canGoForward()) {
                    loadLatch = load; navigating = true; web.goForward(); msg = "forward"
                } else msg = "cannot go forward (no later page)"
                else -> {
                    loadLatch = load; navigating = true; web.reload(); msg = "reloaded"
                }
            }
            ran.countDown()
        }
        ran.await(5, TimeUnit.SECONDS)
        if (navigating) load.await(6, TimeUnit.SECONDS)
        return msg + ": " + js("location.href")
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

    fun screenshot(): ByteArray? {
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        runOnUiThread {
            try {
                val w = web.width
                val h = web.height
                if (w > 0 && h > 0) {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    web.draw(Canvas(bmp))
                    val scaled = if (w > 720) Bitmap.createScaledBitmap(bmp, 720, h * 720 / w, true) else bmp
                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, bos)
                    out = bos.toByteArray()
                }
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return out
    }

    fun downloadsReport(): String {
        if (downloadLog.isEmpty()) return "no downloads yet"
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadLog.joinToString("\n") { (id, name) ->
            var status = "unknown"
            var path = "~/storage/downloads/$name"
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    status = when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> "done"
                        DownloadManager.STATUS_FAILED -> "failed"
                        DownloadManager.STATUS_RUNNING -> "running"
                        DownloadManager.STATUS_PENDING -> "pending"
                        DownloadManager.STATUS_PAUSED -> "paused"
                        else -> "unknown"
                    }
                    val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    val p = if (local != null) Uri.parse(local).path else null
                    if (p != null) path = p.replace("/storage/emulated/0/Download/", "~/storage/downloads/")
                }
            }
            "$status  $path"
        }
    }

    override fun onDestroy() {
        server?.stop()
        stopService(Intent(this, KeepAliveService::class.java))
        super.onDestroy()
    }
}
