package com.havok.arkremote

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class DeviceInfo(
    val name: String,
    val model: String,
    val mac: String?,
    /** "on" / "standby", or null on firmware that doesn't report it. */
    val powerState: String?,
) {
    val isOn: Boolean get() = powerState == null || powerState.equals("on", ignoreCase = true)
}

class UnauthorizedException(message: String) : IOException(message)

/**
 * Talks to Samsung Tizen displays (Odyssey Ark included) over the LAN:
 *  - http://ip:8001/api/v2/            device info + power state
 *  - wss://ip:8002/.../remote.control  remote key presses (needs one-time on-screen approval)
 *  - http://ip:9197 UPnP               absolute volume get/set
 *  - Wake-on-LAN magic packet          power on from network standby
 */
object SamsungApi {
    private const val CLIENT_NAME = "ArkRemote"
    private const val SESSION_IDLE_MS = 45_000L

    // No connection reuse: the monitors drop idle keep-alive sockets, and a dead pooled
    // socket made status checks fail (and monitors look "off") at random.
    private val http = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    // The monitors use a self-signed cert on 8002; this client is only ever pointed at LAN IPs.
    private val ws: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(5, TimeUnit.SECONDS) // notice dead sessions quickly
            .build()
    }

    suspend fun info(ip: String, timeoutMs: Long = 3000): DeviceInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val client = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            client.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = JSONObject(resp.body!!.string())
                val dev = root.optJSONObject("device") ?: JSONObject()
                DeviceInfo(
                    name = dev.optString("name").ifBlank { root.optString("name") },
                    model = dev.optString("modelName"),
                    mac = dev.optString("wifiMac").ifBlank { null },
                    powerState = dev.optString("PowerState").ifBlank { null },
                )
            }
        }.getOrNull()
    }

    // --- Remote-control sessions -----------------------------------------------------------
    // Kept open between presses so D-pad / input keys don't pay a TLS handshake each time.

    private class Session(val socket: WebSocket, val ready: CompletableDeferred<String?>) {
        @Volatile var closed = false
        @Volatile var lastUsed = System.currentTimeMillis()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val reaper = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun session(ip: String, token: String?): Session {
        sessions[ip]?.takeIf { !it.closed }?.let { return it }
        val name = URLEncoder.encode(Base64.encodeToString(CLIENT_NAME.toByteArray(), Base64.NO_WRAP), "UTF-8")
        var url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name"
        if (!token.isNullOrBlank()) url += "&token=$token"

        val ready = CompletableDeferred<String?>()
        lateinit var session: Session
        val socket = ws.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("event")) {
                    "ms.channel.connect" ->
                        ready.complete(msg.optJSONObject("data")?.optString("token")?.ifBlank { null })
                    "ms.channel.unauthorized" ->
                        ready.completeExceptionally(UnauthorizedException("Connection denied — allow ArkRemote in the monitor's Device Connection Manager"))
                    "ms.channel.timeOut" ->
                        ready.completeExceptionally(UnauthorizedException("Approval prompt timed out on the monitor"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                session.closed = true
                sessions.remove(ip, session)
                ready.completeExceptionally(IOException("Can't reach $ip (${t.message})", t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                session.closed = true
                sessions.remove(ip, session)
                ready.completeExceptionally(IOException("Monitor closed the connection ($reason)"))
            }
        })
        session = Session(socket, ready)
        sessions[ip] = session
        reaper.launch {
            while (!session.closed) {
                delay(5000)
                if (System.currentTimeMillis() - session.lastUsed > SESSION_IDLE_MS) drop(ip, session)
            }
        }
        return session
    }

    private fun drop(ip: String, s: Session) {
        s.closed = true
        sessions.remove(ip, s)
        s.socket.close(1000, null)
    }

    /**
     * Sends [keys] in order over the monitor's remote-control session.
     * Returns the token to store (the monitor issues one on first approval).
     */
    suspend fun sendKeys(
        ip: String,
        token: String?,
        keys: List<String>,
        approvalTimeoutMs: Long = 30_000,
    ): String? = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        repeat(2) { // a stale session gets one fresh retry
            val s = session(ip, token)
            try {
                val newToken = withTimeout(approvalTimeoutMs) { s.ready.await() }
                keys.forEachIndexed { i, key ->
                    if (i > 0) delay(350)
                    if (!s.socket.send(keyCommand(key))) throw IOException("Connection to $ip dropped")
                }
                s.lastUsed = System.currentTimeMillis()
                return@withContext newToken ?: token
            } catch (e: TimeoutCancellationException) {
                drop(ip, s)
                throw IOException("Timed out waiting for $ip — accept the prompt on the monitor")
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                drop(ip, s)
                throw e
            } catch (e: IOException) {
                drop(ip, s)
                lastError = e
            }
        }
        throw lastError!!
    }

    private fun keyCommand(key: String): String = JSONObject()
        .put("method", "ms.remote.control")
        .put(
            "params", JSONObject()
                .put("Cmd", "Click")
                .put("DataOfCmd", key)
                .put("Option", "false")
                .put("TypeOfRemote", "SendRemoteKey")
        ).toString()

    // --- UPnP volume -----------------------------------------------------------------------

    private suspend fun renderingControl(ip: String, action: String, args: String): String? =
        withContext(Dispatchers.IO) {
            val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""
            val req = Request.Builder()
                .url("http://$ip:9197/upnp/control/RenderingControl1")
                .header("SOAPACTION", "\"urn:schemas-upnp-org:service:RenderingControl:1#$action\"")
                .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build()
            runCatching { http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
        }

    suspend fun getVolume(ip: String): Int? =
        renderingControl(ip, "GetVolume", "<Channel>Master</Channel>")
            ?.let { Regex("<CurrentVolume>(\\d+)</CurrentVolume>").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    suspend fun setVolume(ip: String, volume: Int) {
        renderingControl(ip, "SetVolume", "<Channel>Master</Channel><DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>")
            ?: throw IOException("Couldn't set volume on $ip")
    }

    // --- Wake-on-LAN -----------------------------------------------------------------------

    suspend fun wakeOnLan(mac: String, ip: String) = withContext(Dispatchers.IO) {
        val macBytes = mac.split(':', '-').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }
        require(macBytes.size == 6) { "Invalid MAC address: $mac" }
        val payload = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { macBytes[it % 6] }
        val targets = listOf("255.255.255.255", ip.substringBeforeLast('.') + ".255", ip)
        DatagramSocket().use { sock ->
            sock.broadcast = true
            repeat(3) {
                for (target in targets) for (port in intArrayOf(9, 7)) {
                    runCatching {
                        sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(target), port))
                    }
                }
                delay(100)
            }
        }
    }
}
