package io.github.staperfeed.facebridge

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException

/**
 * Rootless access to PICO OS 5.13.x face tracking, ported from PicoFacialBridge
 * (MIT, see THIRD_PARTY_NOTICES.md):
 *
 *  1. find Binder service "pxreyetrackingservice" (pvr.IEyeTrackingService);
 *  2. StartAlgorithm (transaction 6) — needs EYE_TRACKING + FACE_TRACKING permissions;
 *  3. transaction 18 with kind=3 returns an ashmem fd of the facial sample ring;
 *  4. native code mmaps it read-only and reads the latest slot every tick.
 *
 * This does not open an OpenXR session, so it keeps working while VRChat has focus.
 */
class PicoTrackingSession {
    companion object {
        private const val TAG = "VrcftBridge"
        private const val SERVICE = "pxreyetrackingservice"
        private const val DESCRIPTOR = "pvr.IEyeTrackingService"
        private const val TX_GET_SHARED_MEMORY = 18
        private const val SHM_FACE = 3
        private const val FACE_STRIDE = 896  // slot size verified on PICO OS 5.13.7
    }

    private var binder: IBinder? = null
    private var started = false

    val isAlive: Boolean get() = binder?.isBinderAlive == true

    @Throws(Exception::class)
    fun open() {
        val b = NativeBridge.nativeLookupService() ?: lookupViaServiceManager()
            ?: throw IOException("$SERVICE not visible to this app")
        binder = b

        val status = NativeBridge.nativeAlgorithm(b, true)
        Log.i(TAG, "StartAlgorithm transport=${status[0]} binder=${status[1]} service=${status[2]}")
        if (status.any { it != 0 }) {
            close()
            throw IOException("StartAlgorithm failed (${status.joinToString("/")}): grant permissions, wear/wake headset")
        }
        started = true

        try {
            mapFaceRing(b)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun lookupViaServiceManager(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("checkService", String::class.java)
            .invoke(null, SERVICE) as IBinder?
    } catch (e: Exception) {
        Log.w(TAG, "ServiceManager.checkService failed: $e")
        null
    }

    private fun mapFaceRing(b: IBinder) {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        var fd: ParcelFileDescriptor? = null
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeInt(SHM_FACE)
            if (!b.transact(TX_GET_SHARED_MEMORY, request, reply, 0) || reply.dataAvail() < 12) {
                throw IOException("shared-memory transaction failed")
            }
            val transport = reply.readInt()
            val service = reply.readInt()
            val present = reply.readInt()
            if (transport != 0 || service != 0 || present == 0) {
                throw IOException("face SHM status=$transport/$service present=$present")
            }
            fd = reply.readFileDescriptor() ?: throw IOException("face SHM missing fd")
            val size = reply.readInt()
            val error = NativeBridge.nativeAttachFaceRing(fd.fd, size)
            if (error != null) throw IOException(error)
            Log.i(TAG, "face ring mapped size=$size (expected stride $FACE_STRIDE)")
        } finally {
            fd?.close()  // the mapping stays valid after the fd is closed
            request.recycle()
            reply.recycle()
        }
    }

    /**
     * PICO keeps the algorithm (and IR cameras) running when its client process dies without calling
     * StopAlgorithm — e.g. after a crash, force-stop or reinstall of the bridge. Sending one stop from a
     * fresh process ends such an orphaned session. Only call when nobody else (VRChat) needs tracking.
     */
    fun stopOrphaned() {
        if (started) return
        val b = NativeBridge.nativeLookupService() ?: lookupViaServiceManager() ?: return
        try {
            val status = NativeBridge.nativeAlgorithm(b, false)
            Log.i(TAG, "orphan cleanup StopAlgorithm ${status.joinToString("/")}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "orphan cleanup failed: $e")
        }
    }

    fun close() {
        NativeBridge.nativeDetachFaceRing()
        val b = binder
        if (started && b != null) {
            started = false
            try {
                val status = NativeBridge.nativeAlgorithm(b, false)
                Log.i(TAG, "StopAlgorithm ${status.joinToString("/")}")
            } catch (e: RuntimeException) {
                Log.w(TAG, "StopAlgorithm error: $e")
            }
        }
        binder = null
    }
}
