package com.havok.arkremote

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address

data class FoundDevice(val ip: String, val info: DeviceInfo)

/** Probes every host on the phone's /24 for the Samsung info endpoint. Takes ~5-10 s. */
object NetworkScanner {
    suspend fun scan(context: Context): List<FoundDevice> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        val own = props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
            ?: return emptyList()
        val prefix = own.hostAddress!!.substringBeforeLast('.')
        val gate = Semaphore(48)
        return coroutineScope {
            (1..254).map { host ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val ip = "$prefix.$host"
                        SamsungApi.info(ip, 1500)?.let { FoundDevice(ip, it) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
