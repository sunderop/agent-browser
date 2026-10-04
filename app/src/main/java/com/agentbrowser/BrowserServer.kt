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
                "/click" -> app.js(
                    "(function(){var e=document.querySelector(${q(arg("selector"))});" +
                    "if(!e)return 'not found';e.click();return 'ok'})()")
                "/type" -> app.js(
                    "(function(){var e=document.querySelector(${q(arg("selector"))});" +
                    "if(!e)return 'not found';e.focus();" +
                    "var P=e instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                    "var d=Object.getOwnPropertyDescriptor(P,'value');" +
                    "if(d&&d.set){d.set.call(e,${q(arg("text"))})}else{e.value=${q(arg("text"))}}" +
                    "e.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok'})()")
                "/eval" -> app.js(arg("js"))
                else -> "unknown endpoint. use: /open /url /text /click /type /eval"
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", out)
    }
}
