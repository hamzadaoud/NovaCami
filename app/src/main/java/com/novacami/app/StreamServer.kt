package com.novacami.app

import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * HTTP server: /video.h264 (raw H.264 stream), /status (JSON), /control (settings).
 * Each viewer has a small bounded queue; a slow viewer is dropped to the next keyframe
 * instead of piling up delay (this is what keeps latency low on bad Wi-Fi).
 */
class StreamServer(
    private val port: Int,
    private val statusJson: () -> String,
    private val onControl: (Map<String, String>) -> String,
    private val requestKeyFrame: () -> Unit,
    private val checkPin: (String?) -> Boolean
) {
    private class Client {
        val queue = ArrayBlockingQueue<ByteArray>(8)
        @Volatile var needKey = true
    }

    /** Wrong-PIN attempts per address: 5 wrong guesses in a minute lock that address out for a minute. */
    private class Tries(var count: Int, var since: Long, var lockedUntil: Long)
    private val tries = ConcurrentHashMap<String, Tries>()

    private fun lockedOut(ip: String): Boolean {
        val t = tries[ip] ?: return false
        return System.currentTimeMillis() < t.lockedUntil
    }

    private fun failed(ip: String) {
        val now = System.currentTimeMillis()
        val t = tries.getOrPut(ip) { Tries(0, now, 0) }
        synchronized(t) {
            if (now - t.since > 60_000) { t.count = 0; t.since = now }
            t.count++
            if (t.count >= 5) { t.lockedUntil = now + 60_000; t.count = 0; t.since = now }
        }
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var server: ServerSocket? = null
    @Volatile private var running = false
    val viewers: Int get() = clients.size

    fun start() {
        running = true
        thread(name = "http-accept") {
            try {
                server = ServerSocket(port)
                while (running) {
                    val s = server!!.accept()
                    thread(name = "http-client") { handle(s) }
                }
            } catch (_: Exception) { }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) { }
    }

    /** After the encoder is restarted (e.g. phone rotated, new picture size) every viewer waits for the new keyframe. */
    fun resetClients() { for (c in clients) { c.queue.clear(); c.needKey = true } }

    /** Called by the encoder for every access unit. */
    fun broadcast(data: ByteArray, key: Boolean) {
        for (c in clients) {
            if (c.needKey) {
                if (!key) continue
                c.needKey = false
            }
            if (!c.queue.offer(data)) {
                c.queue.clear()
                c.needKey = true
                requestKeyFrame()
            }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.tcpNoDelay = true
            val reader = s.getInputStream().bufferedReader()
            val line = reader.readLine() ?: return
            val path = line.split(" ").getOrElse(1) { "/" }
            var headerPin: String? = null
            while (true) {
                val h = reader.readLine()
                if (h.isNullOrEmpty()) break
                val i = h.indexOf(':')
                if (i > 0 && h.substring(0, i).trim().equals("x-pin", true)) headerPin = h.substring(i + 1).trim()
            }
            val out = s.getOutputStream()
            val q = parseQuery(path)
            val protectedPath = path.startsWith("/video") || path.startsWith("/status") || path.startsWith("/control")
            if (protectedPath) {
                val ip = s.inetAddress?.hostAddress ?: "?"
                val given = q["pin"] ?: headerPin
                if (lockedOut(ip)) {
                    text(out, "application/json", """{"ok":false,"error":"too_many_attempts"}""", 429, "Too Many Requests")
                    return
                }
                if (!checkPin(given)) {
                    if (given != null) failed(ip) // no PIN at all is not counted as a guess
                    text(out, "application/json", """{"ok":false,"error":"pin_required"}""", 401, "Unauthorized")
                    return
                }
                tries.remove(ip)
            }
            when {
                path.startsWith("/video") -> stream(s, out)
                path.startsWith("/status") -> text(out, "application/json", statusJson())
                path.startsWith("/control") -> text(out, "application/json", onControl(q))
                else -> text(out, "text/html", PAGE)
            }
        } catch (_: IOException) {
        } finally {
            try { s.close() } catch (_: Exception) { }
        }
    }

    private fun parseQuery(path: String): Map<String, String> =
        path.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            val kv = it.split('=', limit = 2)
            kv[0] to URLDecoder.decode(kv[1], "UTF-8")
        }

    private fun text(out: OutputStream, type: String, body: String, code: Int = 200, reason: String = "OK") {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun stream(s: Socket, out: OutputStream) {
        s.sendBufferSize = 256 * 1024
        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/h264\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        val c = Client()
        clients.add(c)
        requestKeyFrame()
        try {
            while (running) {
                val d = c.queue.poll(1, TimeUnit.SECONDS) ?: continue
                out.write(d); out.flush()
            }
        } catch (_: IOException) {
        } finally { clients.remove(c) }
    }

    companion object {
        const val PAGE = """<!doctype html><html><body style="font-family:sans-serif;padding:20px">
<h3>Novacami is streaming (H.264)</h3>
<p>This stream is protected by a PIN (shown in the Novacami app on the phone).</p>
<p>Open <b>Novacami Desktop</b> and enter the PIN, or use OBS: Media Source, uncheck "Local File", Input = <code id="u"></code>, Input Format = <code>h264</code>.</p>
<p>PIN: <input id="p" inputmode="numeric" maxlength="8" size="10"> <button onclick="go()">OK</button></p>
<pre id="s"></pre>
<script>
function go(){var p=document.getElementById('p').value;location.href='/?pin='+encodeURIComponent(p)}
var pin=new URLSearchParams(location.search).get('pin');
if(pin){
document.getElementById('u').textContent='http://'+location.host+'/video.h264?pin='+pin;
setInterval(function(){fetch('/status?pin='+encodeURIComponent(pin)).then(function(r){return r.text()}).then(function(t){document.getElementById('s').textContent=t})},2000)
}
</script>
</body></html>"""
    }
}
