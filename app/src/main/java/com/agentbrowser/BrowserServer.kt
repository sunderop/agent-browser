package com.agentbrowser

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

// Listens on 127.0.0.1 only, and every request needs the X-Token header.
class BrowserServer(
    private val app: MainActivity,
    private val token: String
) : NanoHTTPD("127.0.0.1", PORT) {

    private fun q(s: String) = JSONObject.quote(s)

    override fun serve(session: IHTTPSession): Response {
        if (session.headers["x-token"] != token) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "text/plain", "unauthorized")
        }
        val p = session.parms
        fun arg(k: String) = p[k] ?: ""

        val out = try {
            when (session.uri) {
                "/open" -> {
                    val ok = app.openUrl(arg("url"))
                    (if (ok) "loaded: " else "timeout, may still be loading: ") + app.js("location.href")
                }
                "/url" -> app.js("location.href")
                "/text" -> {
                    val max = arg("max").toIntOrNull() ?: 20000
                    app.js("(document.body ? document.body.innerText : '')").take(max)
                }
                "/elements" -> app.js(Scripts.ELEMENTS)
                "/click" -> {
                    val id = arg("id").toIntOrNull()
                    if (id != null) app.js(Scripts.click(id))
                    else app.js(
                        "(function(){var e=document.querySelector(${q(arg("selector"))});" +
                        "if(!e)return 'not found';e.click();return 'ok'})()")
                }
                "/type" -> {
                    val id = arg("id").toIntOrNull()
                    if (id != null) app.js(Scripts.type(id, arg("text")))
                    else "error: pass id=<number from /elements>"
                }
                "/press" -> app.js(Scripts.press(arg("key").ifEmpty { "Enter" }))
                "/scroll" -> app.js(Scripts.scroll(arg("dir") != "up"))
                "/back" -> app.js("(function(){history.back();return 'back'})()")
                "/eval" -> app.js(arg("js"))
                else -> "unknown endpoint. use: /open /url /text /elements /click /type /press /scroll /back /eval"
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", out)
    }
}
