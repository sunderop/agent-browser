package com.agentbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
const val APP_VERSION = "1.5"
const val JS_TIMEOUT = "ERR TIMEOUT JavaScript did not respond (page busy, still loading, or WebView frozen)"
private const val FILE_REQ = 77

class MainActivity : Activity() {
    private val tabs = CopyOnWriteArrayList<WebView>()
    @Volatile private var cur = 0
    private val web: WebView get() = tabs[cur]
    private val lastUrl = HashMap<WebView, String>()

    private lateinit var holder: FrameLayout
    private lateinit var tabLabel: TextView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var actionView: TextView

    @Volatile private var loadLatch: CountDownLatch? = null
    @Volatile private var lastLoadError: String? = null
    @Volatile private var jsFail = 0
    @Volatile private var rendererGone = false
    private var recoveries = 0
    private val startTime = System.currentTimeMillis()

    private var server: BrowserServer? = null
    private val downloadLog = CopyOnWriteArrayList<Pair<Long, String>>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            if (server?.isAlive != true) startServer()
            handler.postDelayed(this, 10_000)
        }
    }

    @Volatile var token: String = ""
    @Volatile var askRisky: Boolean = true
    @Volatile var humanPacing: Boolean = true
    @Volatile var paused: Boolean = false
    @Volatile var pauseReason: String = ""
    @Volatile var requestCount: Int = 0
    @Volatile var lastError: String = ""

    private fun newToken(): String {
        val t = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("token", t).apply()
        return t
    }

    private fun refreshInfo() {
        info.text = "127.0.0.1:$PORT  |  token (tap to copy):\n$token"
    }

    private fun btn(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    private fun rowOf(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    // Called when a verification page is detected, or when the agent asks for help.
    // The user clears it by tapping Resume.
    fun setPaused(p: Boolean, reason: String = "") {
        paused = p
        pauseReason = if (p) reason else ""
        runOnUiThread {
            status.text = "PAUSED: $reason\nDo what is needed here, then tap Resume."
            status.visibility = if (p) View.VISIBLE else View.GONE
        }
    }

    fun setAction(s: String) {
        runOnUiThread { actionView.text = "agent: $s" }
    }

    fun tabInfo() = "$cur/${tabs.size}"

    fun networkOk(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun saveTabs() {
        val urls = tabs.joinToString("\n") { lastUrl[it] ?: "" }
        prefs.edit().putString("tabs", urls).putInt("cur", cur).apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(): WebView {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        // Drop the "wv" marker so sign-in pages treat this like a normal mobile browser
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (view != null && url != null) lastUrl[view] = url
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view != null && url != null && url.startsWith("https://")) lastUrl[view] = url
                saveTabs()
                CookieManager.getInstance().flush()
                if (view === web) loadLatch?.countDown()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val d = error?.description?.toString() ?: "load failed"
                    if (!d.contains("ERR_ABORTED")) lastLoadError = d
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true && errorResponse != null && errorResponse.statusCode >= 400) {
                    lastLoadError = "HTTP ${errorResponse.statusCode}"
                }
            }

            // The renderer died: rebuild this WebView and reload its page
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (view != null) {
                    rendererGone = true
                    replaceWebView(view, lastUrl[view])
                }
                return true
            }
        }
        // File inputs open the phone's file/photo picker
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams.createIntent(), FILE_REQ)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
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
        return w
    }

    // Swaps a dead or frozen WebView for a fresh one and reloads its page. UI thread only.
    private fun replaceWebView(old: WebView, url: String?) {
        val idx = tabs.indexOf(old)
        if (idx < 0) return
        holder.removeView(old)
        lastUrl.remove(old)
        try { old.destroy() } catch (e: Exception) { }
        val w = makeWebView()
        tabs[idx] = w
        holder.addView(w, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        w.visibility = if (idx == cur) View.VISIBLE else View.GONE
        recoveries++
        rendererGone = false
        if (url != null && url.startsWith("https://")) w.loadUrl(url)
    }

    fun recoverCurrent(): String {
        val ran = CountDownLatch(1)
        var msg = "ERR WEBVIEW_CRASHED recovery did not finish"
        runOnUiThread {
            msg = try {
                val old = web
                val url = lastUrl[old] ?: old.url
                replaceWebView(old, url)
                "WebView recreated" + (if (url != null) " and reloaded $url" else "")
            } catch (e: Exception) {
                "ERR WEBVIEW_CRASHED recovery failed: ${e.message}"
            }
            ran.countDown()
        }
        ran.await(10, TimeUnit.SECONDS)
        return msg
    }

    private fun showTab(i: Int) {
        cur = i
        tabs.forEachIndexed { j, t -> t.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabLabel.text = "tab ${i + 1}/${tabs.size}"
    }

    private fun addTab(url: String?) {
        val w = makeWebView()
        tabs.add(w)
        holder.addView(w, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        showTab(tabs.size - 1)
        if (url != null) w.loadUrl(url)
    }

    private fun closeTab() {
        if (tabs.size <= 1) return
        val w = tabs.removeAt(cur)
        holder.removeView(w)
        lastUrl.remove(w)
        w.destroy()
        showTab(minOf(cur, tabs.size - 1))
    }

    private fun startServer() {
        try { server?.stop() } catch (e: Exception) { }
        try {
            server = BrowserServer(this).also { it.start(5000, false) }
        } catch (e: Exception) {
            lastError = "SERVER_UNAVAILABLE ${e.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        token = prefs.getString("token", null) ?: newToken()

        info = TextView(this).apply {
            setPadding(24, 16, 24, 8)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("token", token))
                Toast.makeText(this@MainActivity, "Token copied", Toast.LENGTH_SHORT).show()
            }
        }
        refreshInfo()

        val urlBox = EditText(this).apply {
            hint = "https://"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val go = {
            var u = urlBox.text.toString().trim()
            if (u.isNotEmpty()) {
                if (!u.startsWith("http")) u = "https://$u"
                web.loadUrl(u)
            }
        }
        urlBox.setOnEditorActionListener { _, _, _ -> go(); true }
        val urlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("Go") { go() })
        }

        tabLabel = TextView(this).apply { gravity = Gravity.CENTER }
        holder = FrameLayout(this)

        val navBar = rowOf(
            btn("Back") { if (web.canGoBack()) web.goBack() },
            btn("Forward") { if (web.canGoForward()) web.goForward() },
            btn("Reload") { web.reload() }
        )
        val tabBar = rowOf(
            btn("<") { if (cur > 0) showTab(cur - 1) },
            tabLabel,
            btn(">") { if (cur < tabs.size - 1) showTab(cur + 1) },
            btn("+Tab") { addTab(null) },
            btn("Close") { closeTab() }
        )
        val safety = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(CheckBox(this@MainActivity).apply {
                text = "Ask before risky actions"
                isChecked = true
                setOnCheckedChangeListener { _, c -> askRisky = c }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("New token") {
                token = newToken()
                refreshInfo()
                Toast.makeText(this@MainActivity, "New token. Update ~/.browse_token", Toast.LENGTH_LONG).show()
            })
        }
        status = TextView(this).apply {
            setTextColor(0xFFCC0000.toInt())
            setPadding(24, 8, 24, 8)
            visibility = View.GONE
        }
        actionView = TextView(this).apply {
            textSize = 12f
            setPadding(24, 4, 24, 4)
            text = "agent: idle"
        }
        val pacing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(CheckBox(this@MainActivity).apply {
                text = "Human pacing"
                isChecked = true
                setOnCheckedChangeListener { _, c -> humanPacing = c }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("Resume") { setPaused(false) })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(urlBar)
            addView(navBar)
            addView(tabBar)
            addView(safety)
            addView(pacing)
            addView(status)
            addView(actionView)
            addView(holder, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        // Restore the tabs from last time, if any
        val saved = (prefs.getString("tabs", "") ?: "").split("\n").filter { it.startsWith("https://") }
        if (saved.isEmpty()) {
            addTab(null)
        } else {
            saved.forEach { addTab(it) }
            showTab(prefs.getInt("cur", 0).coerceIn(0, tabs.size - 1))
        }

        startForegroundService(Intent(this, KeepAliveService::class.java))
        startServer()
        handler.postDelayed(watchdog, 10_000)
    }

    // Phone's Back gesture/button goes to the previous page first
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_REQ) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun takeLoadError(): String? {
        val e = lastLoadError
        lastLoadError = null
        return e
    }

    fun openUrl(url: String): Boolean {
        val latch = CountDownLatch(1)
        loadLatch = latch
        lastLoadError = null
        runOnUiThread { web.loadUrl(url) }
        return latch.await(20, TimeUnit.SECONDS)
    }

    // action: "back", "forward" or "reload"
    fun nav(action: String): String {
        val ran = CountDownLatch(1)
        val load = CountDownLatch(1)
        var msg = ""
        var navigating = false
        runOnUiThread {
            lastLoadError = null
            when (action) {
                "back" -> if (web.canGoBack()) {
                    loadLatch = load; navigating = true; web.goBack(); msg = "back"
                } else msg = "ERR NOT_FOUND no earlier page to go back to"
                "forward" -> if (web.canGoForward()) {
                    loadLatch = load; navigating = true; web.goForward(); msg = "forward"
                } else msg = "ERR NOT_FOUND no later page to go forward to"
                else -> {
                    loadLatch = load; navigating = true; web.reload(); msg = "reloaded"
                }
            }
            ran.countDown()
        }
        ran.await(5, TimeUnit.SECONDS)
        if (navigating) load.await(6, TimeUnit.SECONDS)
        if (msg.startsWith("ERR")) return msg
        val failure = takeLoadError()
        if (failure != null) return "ERR NAVIGATION_FAILED $failure"
        return msg + ": " + js("location.href")
    }

    // action: "list", "new", "switch" or "close". Tabs are numbered from 0.
    fun tabAction(action: String, arg: String): String {
        val ran = CountDownLatch(1)
        var msg = ""
        runOnUiThread {
            msg = when (action) {
                "new" -> {
                    val u = arg.trim()
                    addTab(if (u.isEmpty()) null else if (u.startsWith("http")) u else "https://$u")
                    "opened tab $cur of ${tabs.size - 1} (now active)"
                }
                "switch" -> {
                    val n = arg.toIntOrNull()
                    if (n != null && n in 0 until tabs.size) {
                        showTab(n)
                        "now on tab $n"
                    } else "ERR NOT_FOUND no such tab (see browse tabs)"
                }
                "close" -> if (tabs.size <= 1) "ERR BAD_REQUEST cannot close the only tab" else {
                    closeTab()
                    "closed; now on tab $cur"
                }
                else -> tabs.mapIndexed { i, t ->
                    "[$i] ${if (i == cur) "ACTIVE" else "      "}  ${t.title ?: ""}\n          " +
                        (t.url ?: "").removePrefix("https://").take(70)
                }.joinToString("\n")
            }
            ran.countDown()
        }
        ran.await(5, TimeUnit.SECONDS)
        return msg
    }

    // Asks the person on the phone to allow or deny an action. Times out as "deny" after 60 s.
    fun confirm(message: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        var dlg: AlertDialog? = null
        runOnUiThread {
            dlg = AlertDialog.Builder(this)
                .setTitle("Agent wants to act")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Allow") { _, _ -> ok = true; latch.countDown() }
                .setNegativeButton("Deny") { _, _ -> latch.countDown() }
                .show()
        }
        val answered = latch.await(60, TimeUnit.SECONDS)
        if (!answered) runOnUiThread { dlg?.dismiss() }
        return ok
    }

    // Runs JavaScript in the current tab. Three timeouts in a row rebuild the WebView.
    fun js(script: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        runOnUiThread {
            try {
                web.evaluateJavascript(script) { r ->
                    out = try {
                        JSONTokener(r ?: "null").nextValue().toString()
                    } catch (e: Exception) {
                        r ?: ""
                    }
                    latch.countDown()
                }
            } catch (e: Exception) {
                out = "ERR WEBVIEW_CRASHED ${e.message}"
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            jsFail++
            if (jsFail >= 3) {
                jsFail = 0
                val r = recoverCurrent()
                return "ERR WEBVIEW_CRASHED the page stopped responding. $r. Run browse elements again."
            }
            return JS_TIMEOUT
        }
        jsFail = 0
        return out
    }

    private fun isBlank(bmp: Bitmap): Boolean {
        val first = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        for (i in 1..7) for (j in 1..7) {
            if (bmp.getPixel(bmp.width * i / 8, bmp.height * j / 8) != first) return false
        }
        return true
    }

    private fun shotOnce(): Pair<ByteArray?, String> {
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        var code = "SCREENSHOT_FAILED"
        runOnUiThread {
            try {
                val w = web.width
                val h = web.height
                if (w > 0 && h > 0) {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    web.draw(Canvas(bmp))
                    if (isBlank(bmp)) {
                        code = "SCREENSHOT_BLANK"
                    } else {
                        val scaled = if (w > 720) Bitmap.createScaledBitmap(bmp, 720, h * 720 / w, true) else bmp
                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 75, bos)
                        out = bos.toByteArray()
                    }
                }
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return Pair(out, code)
    }

    // Retries once if the capture comes out blank
    fun screenshot(): Pair<ByteArray?, String> {
        var r = shotOnce()
        if (r.first == null && r.second == "SCREENSHOT_BLANK") {
            Thread.sleep(500)
            r = shotOnce()
        }
        return r
    }

    fun downloadsReport(): String {
        if (downloadLog.isEmpty()) return "no downloads yet"
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadLog.joinToString("\n") { (id, name) ->
            var icon = "?"
            var word = "unknown"
            var path = "~/storage/downloads/$name"
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> { icon = "✓"; word = "complete" }
                        DownloadManager.STATUS_FAILED -> { icon = "✗"; word = "failed" }
                        DownloadManager.STATUS_RUNNING -> { icon = "↓"; word = "downloading" }
                        DownloadManager.STATUS_PENDING -> { icon = "…"; word = "pending" }
                        DownloadManager.STATUS_PAUSED -> { icon = "="; word = "paused" }
                    }
                    val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    val p = if (local != null) Uri.parse(local).path else null
                    if (p != null) path = p.replace("/storage/emulated/0/Download/", "~/storage/downloads/")
                }
            }
            "$icon $word  ${path.substringAfterLast('/')}  ($path)"
        }
    }

    fun health(): String {
        val jsOk = js("1+1") == "2"
        val ready = js("document.readyState")
        val net = networkOk()
        val serverOk = server?.isAlive == true
        val sb = StringBuilder("AgentBrowser Health\n")
        fun row(name: String, ok: Boolean, note: String = "") {
            sb.append(name.padEnd(12)).append(if (ok) "✓" else "✗")
            if (note.isNotEmpty()) sb.append("  ").append(note)
            sb.append("\n")
        }
        row("SERVER", serverOk)
        row("WEBVIEW", tabs.isNotEmpty())
        row("RENDERER", !rendererGone, if (recoveries > 0) "recovered $recoveries time(s)" else "")
        row("JAVASCRIPT", jsOk)
        row("NETWORK", net)
        row("PAGE", ready == "complete" || ready == "interactive", "readyState=$ready")
        sb.append("\nURL: ").append(js("location.href"))
        sb.append("\nTITLE: ").append(js("document.title").take(60))
        sb.append("\nTAB: ").append(tabInfo()).append("   PAUSED: ").append(paused)
        sb.append("   VERSION: ").append(APP_VERSION)
        sb.append("\nREQUESTS: ").append(requestCount)
        sb.append("   UPTIME: ").append((System.currentTimeMillis() - startTime) / 60000).append(" min")
        if (lastError.isNotEmpty()) sb.append("\nLAST_ERROR: ").append(lastError)
        val all = serverOk && tabs.isNotEmpty() && !rendererGone && jsOk && net
        sb.append("\nSTATUS: ").append(if (all) "READY" else "DEGRADED")
        return sb.toString()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        server?.stop()
        stopService(Intent(this, KeepAliveService::class.java))
        super.onDestroy()
    }
}
