package com.agentbrowser

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Random
import org.json.JSONObject

private const val ACTIONS_PER_HOUR = 150

// Listens on 127.0.0.1 only, and every request needs the X-Token header.
// Errors always look like:  ERR <CODE> <message>
class BrowserServer(private val app: MainActivity) : NanoHTTPD("127.0.0.1", PORT) {

    private val rnd = Random()
    private var lastAct = 0L
    @Volatile private var failStreak = 0
    private val actTimes = ArrayDeque<Long>()

    private fun q(s: String) = JSONObject.quote(s)

    private fun err(code: String, msg: String) = "ERR $code $msg"

    private val risky = Regex(
        "\\b(post|publish|send|submit|delete|remove|buy|purchase|pay|checkout|confirm|tweet|share|reply|comment|subscribe|follow|unfollow|block|report|log ?out|sign ?out|order|upload)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun pause(minMs: Int, maxMs: Int) {
        Thread.sleep((minMs + rnd.nextInt(maxMs - minMs + 1)).toLong())
    }

    // Returns a message if the action must not run now (paused or over the hourly budget).
    // Adds backoff after repeated failures, and (with human pacing) a randomized 0.8-2.5 s gap.
    private fun beforeAction(): String? {
        if (app.paused) {
            return err("HUMAN_REQUIRED", "automation is paused (" +
                app.pauseReason.ifEmpty { "needs the user" } +
                "). Ask the user to finish in the app and tap Resume, then run browse wait-resume.")
        }
        if (failStreak > 0) Thread.sleep(minOf(30_000L, 1500L shl minOf(failStreak, 5)))
        if (!app.humanPacing) return null
        val now = System.currentTimeMillis()
        synchronized(actTimes) {
            while (actTimes.isNotEmpty() && now - actTimes.first() > 3_600_000L) actTimes.removeFirst()
            if (actTimes.size >= ACTIONS_PER_HOUR) {
                return err("RATE_LIMIT", "$ACTIONS_PER_HOUR actions in the last hour. Take a break, or untick Human pacing in the app.")
            }
            actTimes.addLast(now)
        }
        val want = 800L + rnd.nextInt(1700)
        val gap = now - lastAct
        if (gap < want) Thread.sleep(want - gap)
        lastAct = System.currentTimeMillis()
        return null
    }

    private fun gate(block: () -> String): String {
        beforeAction()?.let { return it }
        val r = block()
        val failed = r.startsWith("ERR PAGE_STALLED") || r.startsWith("ERR NAVIGATION_FAILED") ||
            r.startsWith("ERR TIMEOUT") || r.startsWith("ERR WEBVIEW_CRASHED")
        failStreak = if (failed) failStreak + 1 else 0
        return r
    }

    // If the page looks like a CAPTCHA / verification / block page, pause and hand over to the user.
    private fun withCheck(result: String): String {
        val k = app.js(Scripts.CHALLENGE)
        if (k.isNotEmpty() && !k.startsWith("ERR")) {
            app.setPaused(true, "verification check ($k)")
            return result + "\n" + err("HUMAN_REQUIRED",
                "challenge detected (\"$k\"). Automation paused. Ask the user to solve it in the app and tap Resume, then run browse wait-resume.")
        }
        return result
    }

    // Returns null if the action may go ahead, or an error if the user denied it.
    private fun guard(what: String, label: String): String? {
        if (!app.askRisky) return null
        if (!risky.containsMatchIn(label)) return null
        return if (app.confirm("$what: \"$label\"")) null
        else err("DENIED", "the user denied the action (or did not answer in 60 s). Stop and tell the user.")
    }

    private fun waitReady() {
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) {
            val s = app.js("document.readyState+'|'+(document.body?document.body.innerText.length:0)")
            val parts = s.split("|")
            if (parts.size == 2 && parts[0] == "complete" && (parts[1].toIntOrNull() ?: 0) > 0) return
            Thread.sleep(400)
        }
    }

    private fun hostOf(u: String): String = try { URI(u).host ?: "" } catch (e: Exception) { "" }

    private fun openPage(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return err("BAD_REQUEST", "pass a url")
        val url = if (u.contains("://") || u.startsWith("about:")) u else "https://$u"
        if (url.contains("://") && !url.startsWith("https://", ignoreCase = true)) {
            return err("BAD_REQUEST", "only https:// pages can be opened (Android blocks http://). Use https://")
        }
        return gate {
            val ok = app.openUrl(url)
            var note = ""
            if (!ok) {
                val state = app.js("document.readyState")
                if (state != "complete" && state != "interactive") {
                    app.nav("reload")
                    val again = app.js("document.readyState")
                    if (again != "complete" && again != "interactive") {
                        return@gate err("PAGE_STALLED",
                            "page did not become ready after 20 s. Recovery attempted: reload. Result: failed.")
                    }
                    note += "\nNOTE page was slow; reloaded once"
                }
            }
            val failure = app.takeLoadError()
            if (failure != null) return@gate err("NAVIGATION_FAILED", failure)
            waitReady()
            if (app.humanPacing) pause(1200, 3500)
            val finalUrl = app.js("location.href")
            val title = app.js("document.title")
            val fromHost = hostOf(url)
            val toHost = hostOf(finalUrl)
            if (fromHost.isNotEmpty() && toHost.isNotEmpty() && fromHost.removePrefix("www.") != toHost.removePrefix("www.")) {
                note += "\nNOTE redirected to $toHost"
            }
            if (app.js(Scripts.AUTH_HINT) == "true") {
                note += "\nNOTE AUTH_REQUIRED: this looks like a login page. Ask the user to log in inside the app."
            }
            withCheck("loaded: $finalUrl | $title$note")
        }
    }

    private fun newTab(raw: String): String {
        if (raw.trim().startsWith("http://", ignoreCase = true)) {
            return err("BAD_REQUEST", "only https:// pages can be opened (Android blocks http://). Use https://")
        }
        return gate { app.tabAction("new", raw) }
    }

    private fun elements(scope: String, match: String, all: Boolean, refs: Boolean): String {
        var r = app.js(Scripts.elements(scope, match, all, refs))
        var tries = 0
        while (r.contains("no interactive elements found") && tries < 4) {
            Thread.sleep(1000)
            r = app.js(Scripts.elements(scope, match, all, refs))
            tries++
        }
        return if (r.contains("no interactive elements found"))
            r + "\n(still empty: the page may be loading or have nothing clickable. Try browse text)"
        else r
    }

    // Re-lists the page, then finds one element by its label. Returns (id, "") or (null, error).
    private fun resolve(label: String, inputsOnly: Boolean): Pair<Int?, String> {
        app.js(Scripts.elements("", "", true, false))
        val r = app.js(Scripts.match(label, inputsOnly))
        val id = r.toIntOrNull()
        if (id != null) return Pair(id, "")
        if (r.startsWith("ERR")) return Pair(null, r)
        val msg = if (r == "none")
            err("NOT_FOUND", "no element labeled \"$label\". Run browse elements to see what is on the page.")
        else
            err("AMBIGUOUS", "\"$label\" matches several elements. Pick one by number:\n" + r.removePrefix("many:\n"))
        return Pair(null, msg)
    }

    private fun click(idArg: Int?, ref: String, text: String, sel: String): String {
        var find: String? = null
        if (ref.isNotEmpty()) find = Scripts.findRef(ref)
        else if (idArg != null) find = Scripts.findId(idArg)
        else if (text.isNotEmpty()) {
            val (id, e) = resolve(text, false)
            if (id == null) return e
            find = Scripts.findId(id)
        } else if (sel.isNotEmpty()) find = Scripts.findSel(sel)
        if (find == null) return err("BAD_REQUEST", "pass a number, a label, ref=<ref> or selector=<css>")
        val label = app.js(Scripts.label(find))
        if (label.startsWith("ERR")) return label
        guard("Click", label)?.let { return it }
        var res = app.js(Scripts.click(find))
        if (res.startsWith("ERR ELEMENT_STALE") && sel.isNotEmpty() && ref.isEmpty() && idArg == null && text.isEmpty()) {
            res = err("NOT_FOUND", "no element matches that selector")
        }
        return if (res.startsWith("ERR")) res else withCheck(res)
    }

    private fun type(idArg: Int?, ref: String, label: String, text: String, fast: Boolean): String {
        var find: String? = null
        if (ref.isNotEmpty()) find = Scripts.findRef(ref)
        else if (idArg != null) find = Scripts.findId(idArg)
        else if (label.isNotEmpty()) {
            val (id, e) = resolve(label, true)
            if (id == null) return e
            find = Scripts.findId(id)
        }
        if (find == null) return err("BAD_REQUEST", "pass a number, a field label, or ref=<ref>")
        // Short text is typed key by key with natural pauses; long text is pasted in one go.
        if (!app.humanPacing || fast || text.length > 120) return app.js(Scripts.type(find, text))
        val r = app.js(Scripts.focusClear(find))
        if (r != "focused") return r
        pause(250, 700)
        for (cp in text.codePoints().toArray()) {
            app.js(Scripts.typeChar(String(Character.toChars(cp))))
            pause(45, 170)
            if (rnd.nextInt(20) == 0) pause(250, 600)
        }
        app.js(Scripts.CHANGE)
        return "typed ${text.length} chars"
    }

    private fun press(key: String): String {
        val label = if (key == "Enter") app.js(Scripts.ENTER_LABEL) else ""
        guard("Press Enter (submits a form)", label)?.let { return it }
        return withCheck(app.js(Scripts.press(key)))
    }

    private fun scroll(down: Boolean): String {
        if (!app.humanPacing) return app.js(Scripts.scroll(down))
        val steps = 3 + rnd.nextInt(3)
        for (i in 1..steps) {
            app.js(Scripts.scrollStep(down, 0.12 + rnd.nextDouble() * 0.12))
            pause(90, 260)
        }
        return "scrolled"
    }

    private fun waitFor(sel: String, txt: String, secsArg: Int?): String {
        val secs = (secsArg ?: if (sel.isEmpty() && txt.isEmpty()) 2 else 10).coerceIn(1, 30)
        if (sel.isEmpty() && txt.isEmpty()) {
            Thread.sleep(secs * 1000L)
            return "waited ${secs}s"
        }
        val check = if (sel.isNotEmpty()) "(!!document.querySelector(${q(sel)}))"
        else "(document.body?document.body.innerText.toLowerCase().indexOf(${q(txt.lowercase())})>=0:false)"
        val end = System.currentTimeMillis() + secs * 1000L
        var found = false
        while (!found && System.currentTimeMillis() < end) {
            found = app.js(check) == "true"
            if (!found) Thread.sleep(500)
        }
        return if (found) "found" else err("TIMEOUT", "not found within ${secs}s")
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.headers["x-token"] != app.token) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain",
                err("UNAUTHORIZED", "wrong or missing token. Copy the current token from the app."))
        }
        app.requestCount += 1
        val p = session.parms
        fun arg(k: String) = p[k] ?: ""

        // Shown in the app so you can watch what the agent is doing (typed text is never shown)
        val keys = if (session.uri == "/type") listOf("id", "label") else listOf("url", "id", "text", "label", "key", "n")
        app.setAction(session.uri.removePrefix("/") + " " + (keys.map { arg(it) }.firstOrNull { it.isNotEmpty() } ?: "").take(30))

        if (session.uri == "/screenshot") {
            val (bytes, code) = app.screenshot()
            if (bytes == null) {
                return newFixedLengthResponse(Response.Status.OK, "text/plain",
                    err(code, "could not capture the page. Keep the app visible on screen and try again."))
            }
            return newFixedLengthResponse(
                Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
        }

        val out = try {
            when (session.uri) {
                "/open" -> openPage(arg("url"))
                "/url" -> app.js("location.href")
                "/text" -> {
                    val max = arg("max").toIntOrNull() ?: 20000
                    val t = app.js("(document.body ? document.body.innerText : '')")
                    if (t.startsWith("ERR")) t else t.take(max)
                }
                "/state" -> app.js(Scripts.STATE) +
                    "\nTAB: ${app.tabInfo()}\nPAUSED: ${app.paused}\nHUMAN_PACING: ${app.humanPacing}"
                "/health" -> app.health()
                "/recover" -> app.recoverCurrent()
                "/elements" -> elements(arg("scope"), arg("match"), arg("all") == "1", arg("refs") == "1")
                "/inspect" -> {
                    val ref = arg("ref")
                    val id = arg("id").toIntOrNull()
                    when {
                        ref.isNotEmpty() -> app.js(Scripts.inspect(Scripts.findRef(ref)))
                        id != null -> app.js(Scripts.inspect(Scripts.findId(id)))
                        else -> err("BAD_REQUEST", "pass a number from browse elements, or ref=<ref>")
                    }
                }
                "/outline" -> app.js(Scripts.OUTLINE)
                "/click" -> gate { click(arg("id").toIntOrNull(), arg("ref"), arg("text"), arg("selector")) }
                "/type" -> gate { type(arg("id").toIntOrNull(), arg("ref"), arg("label"), arg("text"), arg("fast") == "1") }
                "/press" -> gate { press(arg("key").ifEmpty { "Enter" }) }
                "/scroll" -> gate { scroll(arg("dir") != "up") }
                "/back" -> gate { app.nav("back") }
                "/forward" -> gate { app.nav("forward") }
                "/reload" -> gate { app.nav("reload") }
                "/tabs" -> app.tabAction("list", "")
                "/newtab" -> newTab(arg("url"))
                "/tab" -> app.tabAction("switch", arg("n"))
                "/closetab" -> app.tabAction("close", "")
                "/wait" -> waitFor(arg("selector"), arg("text"), arg("seconds").toIntOrNull())
                "/pause" -> {
                    app.setPaused(true, arg("reason").ifEmpty { "the agent asked for help" })
                    "PAUSED. Waiting for the user to tap Resume in the app. Run browse wait-resume."
                }
                "/waitresume" -> {
                    val secs = (arg("seconds").toIntOrNull() ?: 60).coerceIn(1, 300)
                    val end = System.currentTimeMillis() + secs * 1000L
                    while (app.paused && System.currentTimeMillis() < end) Thread.sleep(500)
                    if (app.paused) err("TIMEOUT", "the user has not resumed yet (still paused: ${app.pauseReason})")
                    else "resumed by the user"
                }
                "/downloads" -> app.downloadsReport()
                "/eval" -> {
                    val code = arg("js")
                    if (app.askRisky && !app.confirm("Run JavaScript:\n" + code.take(300)))
                        err("DENIED", "the user denied running JavaScript")
                    else app.js(code)
                }
                else -> err("BAD_REQUEST", "unknown endpoint")
            }
        } catch (e: Exception) {
            err("SERVER_ERROR", e.message ?: "unexpected error")
        }
        if (out.startsWith("ERR ")) app.lastError = out.take(120)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", out)
    }
}
