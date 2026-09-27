package com.havok.arkremote

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
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

/**
 * Talks to Samsung Tizen displays (Odyssey Ark included) over the LAN:
 *  - http://ip:8001/api/v2/            device info + power state
 *  - wss://ip:8002/.../remote.control  remote key presses (needs one-time on-screen approval)
 *  - Wake-on-LAN magic packet          power on from network standby
 */
object SamsungApi {
    private const val CLIENT_NAME = "ArkRemote"

    private val http = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
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
            .build()
    }

    suspend fun info(ip: String, timeoutMs: Long = 1500): DeviceInfo? = withContext(Dispatchers.IO) {
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

    /**
     * Opens the remote-control channel, sends [keys] in order, and closes it.
     * Returns the token to store (the monitor issues one on first approval).
     */
    suspend fun sendKeys(
        ip: String,
        token: String?,
        keys: List<String>,
        approvalTimeoutMs: Long = 30_000,
    ): String? = withContext(Dispatchers.IO) {
        val name = URLEncoder.encode(Base64.encodeToString(CLIENT_NAME.toByteArray(), Base64.NO_WRAP), "UTF-8")
        var url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name"
        if (!token.isNullOrBlank()) url += "&token=$token"

        val connected = CompletableDeferred<String?>()
        val socket = ws.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("event")) {
                    "ms.channel.connect" ->
                        connected.complete(msg.optJSONObject("data")?.optString("token")?.ifBlank { null })
                    "ms.channel.unauthorized" ->
                        connected.completeExceptionally(IOException("Connection denied — allow ArkRemote in the monitor's Device Connection Manager"))
                    "ms.channel.timeOut" ->
                        connected.completeExceptionally(IOException("Approval prompt timed out on the monitor"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.completeExceptionally(IOException("Can't reach $ip (${t.message})", t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.completeExceptionally(IOException("Monitor closed the connection ($reason)"))
            }
        })

        try {
            val newToken = withTimeout(approvalTimeoutMs) { connected.await() }
            keys.forEachIndexed { i, key ->
                if (i > 0) delay(350)
                socket.send(keyCommand(key))
            }
            delay(300) // let the last frame flush before closing
            newToken ?: token
        } finally {
            socket.close(1000, null)
        }
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
