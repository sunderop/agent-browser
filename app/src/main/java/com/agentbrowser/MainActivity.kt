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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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
private const val FILE_REQ = 77

class MainActivity : Activity() {
    private val tabs = ArrayList<WebView>()
    private var cur = 0
    private val web: WebView get() = tabs[cur]

    private lateinit var holder: FrameLayout
    private lateinit var tabLabel: TextView
    private lateinit var info: TextView

    @Volatile private var loadLatch: CountDownLatch? = null
    private var server: BrowserServer? = null
    private val downloadLog = CopyOnWriteArrayList<Pair<Long, String>>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    @Volatile var token: String = ""
    @Volatile var askRisky: Boolean = true

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
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view === web) loadLatch?.countDown()
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
        w.destroy()
        showTab(minOf(cur, tabs.size - 1))
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(urlBar)
            addView(navBar)
            addView(tabBar)
            addView(safety)
            addView(holder, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        addTab(null)

        startForegroundService(Intent(this, KeepAliveService::class.java))

        server = BrowserServer(this).also {
            it.start(5000, false)
        }
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

    // action: "list", "new", "switch" or "close"
    fun tabAction(action: String, arg: String): String {
        val ran = CountDownLatch(1)
        var msg = ""
        runOnUiThread {
            msg = when (action) {
                "new" -> {
                    val u = arg.trim()
                    addTab(if (u.isEmpty()) null else if (u.startsWith("http")) u else "https://$u")
                    "opened tab ${cur + 1} of ${tabs.size}"
                }
                "switch" -> {
                    val n = arg.toIntOrNull()
                    if (n != null && n in 1..tabs.size) {
                        showTab(n - 1)
                        "now on tab $n"
                    } else "no such tab"
                }
                "close" -> if (tabs.size <= 1) "cannot close the only tab" else {
                    closeTab()
                    "closed; now on tab ${cur + 1} of ${tabs.size}"
                }
                else -> tabs.mapIndexed { i, t ->
                    "${i + 1}${if (i == cur) "*" else " "} ${t.title ?: ""} | ${t.url ?: ""}"
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
