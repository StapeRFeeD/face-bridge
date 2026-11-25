package io.github.staperfeed.facebridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * Finds VRChat's OSCQuery server (mDNS "_oscjson._tcp", name "VRChat-Client-…"), watches the
 * current avatar and configures native VRCFT v2 parameter output to match exactly the
 * parameters that avatar has (prefix such as "FT/v2/" or "v2/", bit-packed sync copies, sign bits).
 * Also reads /avatar/avatarfullfacetracking so the native /tracking/face/v1 blob is only sent to
 * avatars that use VRChat's built-in face tracking.
 */
class OscQueryClient(private val context: Context) {
    companion object {
        /** Latest state for the UI. */
        @Volatile var avatarName: String = ""
        @Volatile var paramCount: Int = 0
        @Volatile var nativeFaceTracking: Boolean = false
        @Volatile var connected: Boolean = false
        @Volatile var visemeParams: List<String> = emptyList()

        /** "Turn off avatar visemes while tracking the mouth" (persisted in Tuning). */
        @Volatile var muteVisemes: Boolean = true
        /** VisemesEnable path this bridge switched off, to restore it later. */
        @Volatile private var mutedVisemePath: String? = null

        /** Switches the avatar's visemes back on if this bridge turned them off. */
        fun restoreVisemes() {
            val path = mutedVisemePath ?: return
            mutedVisemePath = null
            NativeBridge.nativeSendBool(path, true)
            Log.i(TAG, "visemes restored: $path")
        }

        private const val TAG = "VrcftBridge"
        private const val SERVICE_TYPE = "_oscjson._tcp."
        private const val PARAMS = "/avatar/parameters/"
        private const val FALLBACK_PREFIX = "/avatar/parameters/FT/v2/"
        /** A real avatar tree is a few hundred KB; anything bigger is rejected unread. */
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        /** Avatar trees are ~5 levels deep; caps recursion on hostile input. */
        private const val MAX_TREE_DEPTH = 32

        /**
         * VRChat on the headset advertises its OSCQuery server on this device's own address. A service
         * found on another host (anyone on the Wi-Fi can announce "VRChat-Client-…") is ignored.
         */
        private fun isThisDevice(addr: InetAddress?): Boolean {
            if (addr == null) return false
            if (addr.isLoopbackAddress) return true
            return try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .any { nif -> nif.inetAddresses.toList().any { it == addr } }
            } catch (e: Exception) {
                false
            }
        }

        // Combined params that are signed (get a Negative bit in VRCFT's binary sync).
        private val SIGNED = setOf(
            "EyeLeftX", "EyeRightX", "EyeY", "BrowExpressionLeft", "BrowExpressionRight",
            "CheekPuffSuckLeft", "CheekPuffSuckRight", "CheekPuffSuck", "JawX", "JawZ", "MouthX",
            "MouthUpperX", "MouthLowerX", "SmileFrownRight", "SmileFrownLeft", "SmileFrown",
            "SmileSadRight", "SmileSadLeft", "SmileSad", "TongueX", "TongueY", "TongueArchY", "TongueShape",
        )
    }

    @Volatile private var running = false
    @Volatile private var host: String? = null
    @Volatile private var port = 0
    private var thread: Thread? = null
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var resolving = false

    @Volatile var status: String = "OSCQuery: searching for VRChat"
        private set

    private val slotNames: List<String> by lazy {
        (0 until NativeBridge.nativeParamSlotCount()).map { NativeBridge.nativeParamSlotName(it) ?: "" }
    }

    fun start() {
        running = true
        applyFallback()
        startDiscovery()
        thread = Thread(::pollLoop, "vrcft-oscquery").also { it.start() }
    }

    @Synchronized
    fun stop() {
        restoreVisemes()
        running = false
        connected = false
        thread?.interrupt()
        thread = null
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discovery = null
    }

    /** Until VRChat is found: the "FT/v2/" combined set with 3-bit sync, like common VRCFT templates. */
    private fun applyFallback() {
        val flags = IntArray(slotNames.size)
        val ueCount = 102
        for (i in slotNames.indices) {
            val name = slotNames[i]
            flags[i] = when {
                name == "EyeTrackingActive" || name == "LipTrackingActive" -> 1
                i < ueCount -> 0
                else -> 1 or (3 shl 1) or (if (name in SIGNED) 0x20 else 0)
            }
        }
        NativeBridge.nativeConfigureParams(FALLBACK_PREFIX, PARAMS, flags, true, -1)
        status = "OSCQuery: VRChat not found yet, sending FT/v2 defaults"
    }

    private fun startDiscovery() {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { Log.i(TAG, "OSCQuery discovery started") }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "OSCQuery discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {
                if (info.serviceName.startsWith("VRChat-Client")) {
                    Log.i(TAG, "OSCQuery lost ${info.serviceName}")
                }
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith("VRChat-Client") || resolving) return
                resolving = true
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        Log.w(TAG, "OSCQuery resolve failed: $errorCode")
                    }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        resolving = false
                        if (!isThisDevice(i.host)) {
                            Log.w(TAG, "OSCQuery ignored ${i.serviceName} at ${i.host?.hostAddress}: not this headset")
                            return
                        }
                        host = i.host?.hostAddress
                        port = i.port
                        Log.i(TAG, "OSCQuery found ${i.serviceName} at $host:$port")
                    }
                })
            }
        }
        discovery = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices: $e")
        }
    }

    @Synchronized
    private fun restartDiscovery() {
        if (!running) return
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discovery = null
        resolving = false
        startDiscovery()
    }

    private fun pollLoop() {
        var lastAvatar: String? = null
        var lastFullFt: Int? = null
        var failures = 0
        while (running) {
            val h = host
            if (h != null) {
                try {
                    val avatar = JSONObject(get(h, port, "/avatar/change")).optJSONArray("VALUE")?.optString(0)
                    if (avatar != null && avatar != lastAvatar) {
                        // Give VRChat a moment to publish the new avatar's parameters.
                        if (lastAvatar != null) SystemClock.sleep(1500)
                        val fullFt = configureFromTree(JSONObject(get(h, port, "/avatar")), avatar)
                        lastAvatar = avatar
                        lastFullFt = fullFt
                    } else if (avatar != null) {
                        val ft = readFullFt(JSONObject(get(h, port, "/avatar/avatarfullfacetracking")))
                        if (ft != lastFullFt) {
                            lastFullFt = ft
                            lastAvatar = null  // re-read the tree
                        }
                    }
                    failures = 0
                } catch (e: InterruptedException) {
                    return
                } catch (e: java.io.FileNotFoundException) {
                    // 404: VRChat is up but has not published the avatar yet (e.g. still loading). Keep polling.
                    status = "OSCQuery: VRChat found, waiting for avatar"
                } catch (e: Throwable) {
                    // Throwable: hostile/garbled JSON can also raise StackOverflowError or OutOfMemoryError,
                    // which must not kill this thread (and with it the process).
                    if (e is VirtualMachineError && e !is StackOverflowError && e !is OutOfMemoryError) throw e
                    Log.w(TAG, "OSCQuery $h:$port failed: $e")
                    if (++failures >= 3) {
                        // VRChat restarted (new port) or went away: rediscover.
                        status = "OSCQuery: lost VRChat, searching"
                        connected = false
                        host = null
                        lastAvatar = null
                        failures = 0
                        restartDiscovery()
                    }
                }
            }
            try { Thread.sleep(2000) } catch (e: InterruptedException) { return }
        }
    }

    private fun readFullFt(node: JSONObject): Int {
        val v = node.optJSONArray("VALUE") ?: return -1
        return if (v.optBoolean(0, false)) 1 else 0
    }

    /** Returns avatarfullfacetracking (-1/0/1) after configuring native output. */
    private fun configureFromTree(root: JSONObject, avatarId: String): Int {
        val paths = HashSet<String>()
        val visemeValues = HashMap<String, Boolean>()
        var fullFt = -1
        var avatarName = ""
        fun walk(n: JSONObject, depth: Int) {
            if (depth > MAX_TREE_DEPTH) return
            val full = n.optString("FULL_PATH", "")
            if (full == "/avatar/avatarfullfacetracking") fullFt = readFullFt(n)
            if (full == "/avatar/name") avatarName = n.optJSONArray("VALUE")?.optString(0) ?: ""
            if (full.startsWith(PARAMS) && n.has("TYPE")) {
                paths.add(full)
                if (n.optString("TYPE") == "T") n.optJSONArray("VALUE")?.let { visemeValues[full] = it.optBoolean(0) }
            }
            val contents = n.optJSONObject("CONTENTS") ?: return
            for (key in contents.keys()) contents.optJSONObject(key)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)

        // Find the v2 prefix: the most common "<anything>v2/" before a known slot name.
        val prefixCount = HashMap<String, Int>()
        for (p in paths) {
            val idx = p.lastIndexOf("v2/")
            if (idx < 0) continue
            val base = p.substring(idx + 3)
            if (slotNames.any { base == it || base.startsWith(it) }) {
                val prefix = p.substring(0, idx + 3)
                prefixCount[prefix] = (prefixCount[prefix] ?: 0) + 1
            }
        }
        val prefix = prefixCount.maxByOrNull { it.value }?.key ?: FALLBACK_PREFIX

        // Status toggles (VRCFT avatars use LipTrackingActive to switch visemes off while lip tracking
        // drives the mouth). Accept any prefix, e.g. /avatar/parameters/FT/LipTrackingActive.
        val statusPath = paths.filter { it.endsWith("/LipTrackingActive") || it.endsWith("/EyeTrackingActive") }
            .minByOrNull { it.length }
        val statusPrefix = statusPath?.substring(0, statusPath.lastIndexOf('/') + 1) ?: PARAMS

        val flags = IntArray(slotNames.size)
        var found = 0
        for (i in slotNames.indices) {
            val name = slotNames[i]
            if (name == "EyeTrackingActive" || name == "LipTrackingActive") {
                if (statusPrefix + name in paths) flags[i] = 1
                continue
            }
            val base = prefix + name
            var f = if (base in paths) 1 else 0
            var bits = 0
            while (bits < 8 && "$base${1 shl bits}" in paths) bits++
            f = f or (bits shl 1)
            if ("${base}Negative" in paths) f = f or 0x20
            if (f != 0) found++
            flags[i] = f
        }
        NativeBridge.nativeConfigureParams(prefix, statusPrefix, flags, found > 0, fullFt)
        status = "OSCQuery: $avatarName — $found VRCFT params ($prefix), native FT=${fullFt == 1}"
        Companion.avatarName = avatarName
        Companion.paramCount = found
        Companion.nativeFaceTracking = fullFt == 1
        Companion.connected = true
        Log.i(TAG, "avatar $avatarId '$avatarName': $found v2 params, prefix=$prefix, status=$statusPrefix, fullFaceTracking=$fullFt")
        // Avatars with a VisemesEnable menu toggle keep mic visemes on top of tracked lips unless it is off.
        mutedVisemePath = null
        val visemeToggle = paths.filter { it.endsWith("/VisemesEnable") && !it.contains("/State/") }.minByOrNull { it.length }
        if (visemeToggle != null && muteVisemes && found > 0 && visemeValues[visemeToggle] != false) {
            NativeBridge.nativeSendBool(visemeToggle, false)
            mutedVisemePath = visemeToggle
            Log.i(TAG, "visemes turned off while lip tracking: $visemeToggle")
        }
        val visemeRelated = paths.filter { p -> listOf("visem", "liptrack", "eyetrack", "trackingactive").any { p.lowercase().contains(it) } }
        Log.i(TAG, "avatar viseme/tracking params: ${visemeRelated.sorted().joinToString(" ")}")
        Companion.visemeParams = visemeRelated.sorted()
        return fullFt
    }

    private fun get(host: String, port: Int, path: String): String {
        val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 3000
        c.instanceFollowRedirects = false
        try {
            val declared = c.contentLengthLong
            if (declared > MAX_RESPONSE_BYTES) throw IOException("OSCQuery response too large ($declared bytes)")
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_RESPONSE_BYTES) throw IOException("OSCQuery response too large")
                    out.write(buf, 0, n)
                }
                return out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }
}
