package io.github.staperfeed.facebridge

import android.os.IBinder

/** JNI surface of libvrcftbridge.so. All per-frame work happens in native code. */
object NativeBridge {
    const val SOURCE_AUTO = 0
    const val SOURCE_PICO = 1
    const val SOURCE_TEST = 3

    const val STAT_SENT = 0
    const val STAT_SEND_ERRORS = 1
    const val STAT_NO_DATA = 2
    const val STAT_AVATAR_FT = 3
    const val STAT_ACTIVE_SOURCE = 4
    const val STAT_RING_ATTACHED = 5
    const val STAT_FACE_FRAMES = 6
    const val STAT_MS_SINCE_FACE = 7
    const val STAT_FACE_VALIDITY = 8
    const val STAT_COUNT = 9

    init {
        System.loadLibrary("vrcftbridge")
    }

    external fun nativeInit(sendPort: Int): Boolean
    external fun nativeSetSource(source: Int)
    external fun nativeSetRespectAvatarFlag(respect: Boolean)
    external fun nativeSendChatbox(text: String): Boolean
    /** Sends one OSC bool (T/F) to VRChat, e.g. an avatar parameter. Not for the hot path. */
    external fun nativeSendBool(address: String, value: Boolean): Boolean
    /** flags per slot: bit0 float, bits1..4 binary bit count, bit5 Negative. fullFaceTracking -1/0/1. */
    external fun nativeConfigureParams(prefix: String, statusPrefix: String, flags: IntArray, enabled: Boolean, fullFaceTracking: Int)
    external fun nativeParamSlotCount(): Int
    external fun nativeParamSlotName(slot: Int): String?
    external fun nativeCalibrationPhase(phase: Int)
    /** Latest raw 52 shapes (ARKit order); false when no fresh frame. */
    external fun nativeGetLiveShapes(out: FloatArray): Boolean
    /** [lower(52), upper(52)] or null when too little tracking data was recorded. */
    external fun nativeCalibrationFinish(): FloatArray?
    external fun nativeSetCalibration(data: FloatArray?, enabled: Boolean)
    /** 0 = remove resting offset only, 1 = full per-shape range stretch. */
    external fun nativeSetCalibrationRange(range: Float)
    external fun nativeSetFilters(flags: Int, deadzone: Float, smoothing: Float)
    external fun nativeSetTuning(faceGain: Float, eyeGain: Float, sendEyeOsc: Boolean, jawGain: Float)
    external fun nativeRunLoop(hz: Int)
    external fun nativeStop()
    external fun nativeShutdown()
    external fun nativeGetStats(out: LongArray)
    external fun nativeBackendInfo(): String
    external fun nativeSetStatus(text: String)

    // PICO tracking service
    external fun nativeLookupService(): IBinder?
    /** [transport, binderException, serviceStatus]; all 0 on success. */
    external fun nativeAlgorithm(binder: IBinder, start: Boolean): IntArray
    /** null on success, otherwise the reason. */
    external fun nativeAttachFaceRing(fd: Int, size: Int): String?
    external fun nativeDetachFaceRing()
    external fun nativeRingDebug(): String
}
