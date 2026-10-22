package com.nuvio.tv.relay.standalone

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Tiny LAN-only HTTP server that lets a phone add or remove add-on manifest URLs.
 * It runs only while the "Add from phone" dialog is open and every request must carry the one-time token
 * that is embedded in the QR code.
 */
internal class PhoneAddonServer(private val token: String, private val host: Host) {
    data class AddonRow(val url: String, val name: String)

    interface Host {
        fun list(): List<AddonRow>
        /** Returns a user-facing message; throws with a readable message on failure. */
        fun add(rawUrl: String): String
        fun remove(url: String): String
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    /** Starts listening on the first free port in 9080..9089 and returns it, or null if none is free. */
    fun start(): Int? {
        for (port in 9080..9089) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port), 8)
                serverSocket = socket
                running = true
                worker = Thread({ acceptLoop(socket) }, "relay-phone-server").also { it.isDaemon = true; it.start() }
                return port
            } catch (_: Exception) { }
        }
        return null
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            try { client.soTimeout = 5000; handle(client) } catch (_: Exception) { } finally { runCatching { client.close() } }
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() < 8192) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
        }
        return null
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("content-length", true)) contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
        }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val given = query.split('&').firstOrNull { it.startsWith("t=") }?.substring(2)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()
        if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            respond(client, 403, "text/plain", "Forbidden")
            return
        }
        when {
            method == "GET" && path == "/" -> respond(client, 200, "text/html", PAGE)
            method == "GET" && path == "/api/addons" -> {
                val arr = JSONArray()
                host.list().forEach { arr.put(JSONObject().put("url", it.url).put("name", it.name)) }
                respond(client, 200, "application/json", JSONObject().put("addons", arr).toString())
            }
            method == "POST" && (path == "/api/add" || path == "/api/remove") -> {
                if (contentLength !in 1..8192) { respond(client, 400, "application/json", error("Bad request")); return }
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) { val n = input.read(body, read, contentLength - read); if (n < 0) break; read += n }
                val url = runCatching { JSONObject(String(body, 0, read, Charsets.UTF_8)).optString("url").trim() }.getOrDefault("")
                if (url.isBlank()) { respond(client, 400, "application/json", error("Missing URL")); return }
                try {
                    val message = if (path == "/api/add") host.add(url) else host.remove(url)
                    respond(client, 200, "application/json", JSONObject().put("ok", true).put("message", message).toString())
                } catch (e: Exception) {
                    respond(client, 200, "application/json", error(e.message ?: "Failed"))
                }
            }
            else -> respond(client, 404, "text/plain", "Not found")
        }
    }

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message).toString()

    private fun respond(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    private companion object {
        const val PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nuvio Relay</title><style>
body{margin:0;background:#0b0f17;color:#e8eef8;font:16px/1.5 -apple-system,Roboto,Segoe UI,sans-serif}
main{max-width:560px;margin:0 auto;padding:24px 18px 48px}h1{font-size:24px;margin:8px 0 2px}h2{font-size:13px;letter-spacing:.12em;color:#5ebeff;margin:28px 0 8px}
p{color:#96a4ba;margin:4px 0 14px}textarea{width:100%;box-sizing:border-box;background:#121826;color:#e8eef8;border:1px solid #1b2334;border-radius:12px;padding:12px;font:15px monospace}
button{background:#5ebeff;color:#081624;border:0;border-radius:12px;padding:12px 18px;font-size:16px;font-weight:600;width:100%;margin-top:10px}
#msg{margin-top:12px;min-height:1.4em}.ok{color:#69d3b0}.bad{color:#ff8278}
li{background:#121826;border-radius:12px;padding:12px 14px;margin-bottom:8px;list-style:none;display:flex;gap:10px;align-items:center}
li div{flex:1;min-width:0}li b{display:block}li small{color:#68768c;word-break:break-all}li button{width:auto;margin:0;padding:8px 12px;background:#1b2334;color:#ff8278;font-size:14px}
ul{padding:0;margin:0}</style></head><body><main>
<h1>Nuvio Relay</h1><p>Add an add-on manifest or install link. / أضف رابط الإضافة (manifest).</p>
<textarea id="u" rows="3" placeholder="https://.../manifest.json  or  stremio://..."></textarea>
<button id="add">Add add-on / إضافة</button><div id="msg"></div>
<h2>YOUR ADD-ONS</h2><ul id="list"></ul></main>
<script>
var T=new URLSearchParams(location.search).get('t')||'';
function api(p,b){return fetch(p+'?t='+encodeURIComponent(T),b?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}:{}).then(function(r){return r.json()})}
function say(t,ok){var m=document.getElementById('msg');m.textContent=t;m.className=ok?'ok':'bad'}
function load(){api('/api/addons').then(function(d){var l=document.getElementById('list');l.textContent='';(d.addons||[]).forEach(function(a){
var li=document.createElement('li'),box=document.createElement('div'),n=document.createElement('b'),s=document.createElement('small'),x=document.createElement('button');
n.textContent=a.name;s.textContent=a.url;x.textContent='Remove';x.onclick=function(){api('/api/remove',{url:a.url}).then(function(r){say(r.ok?r.message:r.error,r.ok);load()})};
box.appendChild(n);box.appendChild(s);li.appendChild(box);li.appendChild(x);l.appendChild(li)})}).catch(function(){say('Cannot reach the TV',false)})}
document.getElementById('add').onclick=function(){var v=document.getElementById('u').value.trim();if(!v)return;say('Checking add-on...',true);
api('/api/add',{url:v}).then(function(r){say(r.ok?r.message:r.error,r.ok);if(r.ok){document.getElementById('u').value='';load()}}).catch(function(){say('Cannot reach the TV',false)})};
load();
</script></body></html>"""
    }
}
