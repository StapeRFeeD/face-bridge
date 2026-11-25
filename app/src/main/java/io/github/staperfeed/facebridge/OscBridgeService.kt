package io.github.staperfeed.facebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Foreground service that owns the native 60 Hz loop. Keeps running while VRChat has focus.
 */
class OscBridgeService : Service() {

    companion object {
        private const val TAG = "VrcftBridge"
        private const val CHANNEL_ID = "vrcft_bridge"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "io.github.staperfeed.facebridge.START"
        const val ACTION_STOP = "io.github.staperfeed.facebridge.STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_RESPECT_AVATAR = "respect_avatar"

        const val OSC_SEND_PORT = 9000
        const val LOOP_HZ = 60

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Last requested data source (for the UI). */
        @Volatile
        var lastSource: Int = NativeBridge.SOURCE_AUTO
            private set

        /** Human-readable PICO session problem, or null when the session is open. */
        @Volatile
        var sessionProblem: String? = null
            internal set

        /** Why tracking is on standby (cameras off to save battery), or null while it is wanted. */
        @Volatile
        var standbyReason: String? = null
            private set

        /** Open calibration screens: track even without VRChat. A counter, not a flag, so an old screen's
         *  onDestroy running after a new screen's onCreate cannot switch forced tracking off. */
        private val forceCount = java.util.concurrent.atomic.AtomicInteger(0)
        val forceTracking: Boolean get() = forceCount.get() > 0
        fun acquireForceTracking() { forceCount.incrementAndGet() }
        fun releaseForceTracking() { if (forceCount.decrementAndGet() < 0) forceCount.set(0) }

        fun start(context: Context, source: Int, respectAvatar: Boolean) {
            val i = Intent(context, OscBridgeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_RESPECT_AVATAR, respectAvatar)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OscBridgeService::class.java).setAction(ACTION_STOP))
        }
    }

    private var worker: Thread? = null
    private var supervisor: Thread? = null
    private var oscQuery: OscQueryClient? = null
    @Volatile private var supervising = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLoop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val source = intent?.getIntExtra(EXTRA_SOURCE, NativeBridge.SOURCE_AUTO) ?: NativeBridge.SOURCE_AUTO
        val respect = intent?.getBooleanExtra(EXTRA_RESPECT_AVATAR, true) ?: true
        NativeBridge.nativeSetSource(source)
        NativeBridge.nativeSetRespectAvatarFlag(respect)
        lastSource = source

        // Optional adb overrides: --ef face_gain 2.0 --ef eye_gain 1.5 --ez eye_osc true (saved).
        var tuning = Tuning.load(this)
        if (intent != null && (intent.hasExtra("face_gain") || intent.hasExtra("eye_gain") || intent.hasExtra("eye_osc"))) {
            tuning = Tuning.Values(
                intent.getFloatExtra("face_gain", tuning.faceGain),
                intent.getFloatExtra("eye_gain", tuning.eyeGain),
                intent.getBooleanExtra("eye_osc", tuning.eyeOsc),
            )
            Tuning.save(this, tuning)
        } else {
            Tuning.apply(tuning)
        }

        // Optional adb overrides: --ei filters <bitmask> --ef deadzone 0.08 --ef smoothing 0.3 (saved).
        var filters = Filters.load(this)
        if (intent != null && (intent.hasExtra("filters") || intent.hasExtra("deadzone") || intent.hasExtra("smoothing"))) {
            filters = Filters.Values(
                intent.getIntExtra("filters", filters.flags),
                intent.getFloatExtra("deadzone", filters.deadzone),
                intent.getFloatExtra("smoothing", filters.smoothing),
            )
            Filters.save(this, filters)
        } else {
            Filters.apply(filters)
        }

        DevMode.load(this)
        Calibration.apply(Calibration.load(this))

        if (worker == null) startLoop()
        // Debug: --es chatbox "text" sends a VRChat chatbox message to prove OSC input works.
        intent?.getStringExtra("chatbox")?.let { NativeBridge.nativeSendChatbox(it) }
        // Restarted by the system after being killed: resume with default settings.
        return START_STICKY
    }

    private fun startLoop() {
        if (!NativeBridge.nativeInit(OSC_SEND_PORT)) {
            Log.e(TAG, "nativeInit failed")
            return
        }
        Log.i(TAG, "backend: ${NativeBridge.nativeBackendInfo()}")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Held only while a tracking session is open (see holdWakeLock).
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VrcftBridge:loop").apply {
            setReferenceCounted(false)
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            NativeBridge.nativeRunLoop(LOOP_HZ)   // blocks until nativeStop()
        }, "vrcft-osc-loop").also { it.start() }

        oscQuery = OscQueryClient(this).also { it.start() }

        supervising = true
        supervisor = Thread(::superviseTracking, "vrcft-pico-session").also { it.start() }
        isRunning = true
    }

    @Volatile private var screenOn = true
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            Log.i(TAG, "screen ${if (screenOn) "on" else "off"}")
            supervisor?.interrupt()   // react now instead of on the next tick
        }
    }

    /** VRChat binds UDP 9000 (0.0.0.0) while it runs; works even when OSCQuery/mDNS is unavailable. */
    private fun oscPortInUse(): Boolean = try {
        DatagramSocket(null).use {
            it.reuseAddress = false
            it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), OSC_SEND_PORT))
        }
        false
    } catch (e: BindException) {
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Decides whether PICO face tracking should run. The tracking algorithm + IR cameras are the real
     * battery cost (see docs/power-baseline.md), so they only run while they are useful.
     */
    private fun trackingWanted(vrchatRunning: Boolean): String? {
        val source = lastSource
        return when {
            source == NativeBridge.SOURCE_TEST -> "Test pattern"
            forceTracking -> null
            !screenOn -> "Headset asleep"
            !Power.onlyWithVrchat(this) -> null
            !vrchatRunning -> "Waiting for VRChat"
            OscQueryClient.connected && OscQueryClient.avatarName.isNotEmpty() &&
                OscQueryClient.paramCount == 0 && !OscQueryClient.nativeFaceTracking -> "Avatar has no face tracking"
            else -> null
        }
    }

    private fun holdWakeLock(hold: Boolean) {
        val wl = wakeLock ?: return
        if (hold && !wl.isHeld) wl.acquire() else if (!hold && wl.isHeld) wl.release()
    }

    /**
     * Keeps the PICO tracking session open only while it is wanted: closes it (StopAlgorithm → cameras
     * off) on standby, reopens it when needed again. While open: reopens when the Binder dies or frames
     * stop (policy from PicoFacialBridge). Failed opens back off 5 → 30 s; "system is sleep" waits for
     * the screen-on broadcast instead of polling.
     */
    private fun superviseTracking() {
        val session = PicoTrackingSession()
        val stats = LongArray(NativeBridge.STAT_COUNT)
        var active = false
        var openedAt = 0L
        var nextAttempt = 0L
        var backoff = 5_000L
        var nextDebugLog = 0L
        var framesAtOpen = 0L
        var vrchatRunning = false
        var nextVrchatCheck = 0L
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        screenOn = pm.isInteractive
        if (!oscPortInUse()) session.stopOrphaned()   // end a session left running by a killed process
        try {
            while (supervising) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextVrchatCheck) {
                    vrchatRunning = OscQueryClient.connected || oscPortInUse()
                    nextVrchatCheck = now + 2_000
                }
                val reason = trackingWanted(vrchatRunning)
                if (reason != standbyReason) Log.i(TAG, "tracking ${reason?.let { "standby: $it" } ?: "wanted"}")
                standbyReason = reason

                if (reason != null) {
                    if (active) {
                        session.close()
                        active = false
                        Log.i(TAG, "tracking stopped (cameras off): $reason")
                    }
                    sessionProblem = null
                    backoff = 5_000L
                    nextAttempt = 0L
                    holdWakeLock(false)
                } else if (active) {
                    NativeBridge.nativeGetStats(stats)
                    val sinceFace = stats[NativeBridge.STAT_MS_SINCE_FACE]
                    // Never published: the tracker writes nothing until a face is seen, so wait 30 s
                    // before cycling the camera. After frames stop: 10 s, like PicoFacialBridge.
                    val stale = if (sinceFace < 0 || stats[NativeBridge.STAT_FACE_FRAMES] == framesAtOpen) {
                        now - openedAt > 30_000
                    } else {
                        now - openedAt > 10_000 && sinceFace > 10_000
                    }
                    if (!session.isAlive || stale) {
                        Log.w(TAG, "PICO session unhealthy (alive=${session.isAlive} sinceFace=$sinceFace), reopening")
                        session.close()
                        active = false
                        nextAttempt = now + 1_000
                    }
                } else if (now >= nextAttempt) {
                    try {
                        session.open()
                        sessionProblem = null
                        active = true
                        backoff = 5_000L
                        holdWakeLock(true)
                        openedAt = SystemClock.elapsedRealtime()
                        NativeBridge.nativeGetStats(stats)
                        framesAtOpen = stats[NativeBridge.STAT_FACE_FRAMES]
                    } catch (e: Exception) {
                        val asleep = "/-7" in (e.message ?: "")
                        Log.w(TAG, "PICO session open failed: $e")
                        NativeBridge.nativeSetStatus("PICO: ${e.message ?: e}")
                        sessionProblem = if (asleep) "Put on the headset" else (e.message ?: e.toString())
                        // Asleep: the screen-on broadcast interrupts the wait; otherwise back off.
                        nextAttempt = now + if (asleep) 60_000L else backoff
                        backoff = (backoff * 2).coerceAtMost(30_000L)
                    }
                }
                if (DevMode.enabled && now >= nextDebugLog) {
                    Log.i(TAG, NativeBridge.nativeRingDebug() + " | standby=" + standbyReason + " | " + (oscQuery?.status ?: ""))
                    nextDebugLog = now + 1_000
                }
                try {
                    Thread.sleep(if (active) 500 else 1_000)
                } catch (e: InterruptedException) {
                    nextAttempt = 0L   // screen turned on: try right away (or stopping: loop condition ends it)
                }
            }
        } finally {
            session.close()
            holdWakeLock(false)
        }
    }

    private fun stopLoop() {
        if (worker != null) try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
        oscQuery?.stop()
        oscQuery = null
        supervising = false
        supervisor?.let { it.interrupt(); it.join(3_000) }   // wake it from its sleep; it closes the PICO session
        supervisor = null
        NativeBridge.nativeStop()
        // The loop wakes at least every 100 ms; nativeShutdown also waits for it before closing the socket.
        worker?.join(1_000)
        worker = null
        NativeBridge.nativeShutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        sessionProblem = null
        standbyReason = null
        isRunning = false
    }

    override fun onDestroy() {
        stopLoop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Face Bridge", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Face tracking -> VRChat OSC bridge"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OscBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Face Bridge")
            .setContentText("Streaming face tracking to VRChat")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
