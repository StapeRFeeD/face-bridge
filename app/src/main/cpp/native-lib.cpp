// native-lib.cpp — PICO 4 Pro face/eye tracking -> VRChat native OSC (/tracking/face/v1)
//
// Data source: PICO OS 5.13.x "pxreyetrackingservice" (Binder, descriptor pvr.IEyeTrackingService)
// and its read-only shared-memory ring of facial samples. The Binder transaction codes, arguments
// and ring layout come from PicoFacialBridge (MIT, (c) 2026 WolalaQAQ and contributors,
// (c) 2026 thoricelli) — see THIRD_PARTY_NOTICES.md.
//
// Hot loop rules: no heap allocation, no JNI calls, no logging per frame.
// Everything the loop touches is static storage allocated once at load time.

#include <jni.h>
#include <android/binder_ibinder.h>
#include <android/binder_ibinder_jni.h>
#include <android/binder_parcel.h>
#include <android/log.h>

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <climits>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>

#define LOG_TAG "VrcftBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
constexpr int kArkitCount = 52;
constexpr int kUeCount = 102;          // VRChat /tracking/face/v1 payload size
constexpr int kPacketSize = 132;       // 20 addr + 4 typetag + 4 len + 102 blob + 2 pad
constexpr int kBlobOffset = 28;
constexpr int kGazeFloats = 6;         // left xyz, right xyz (unit vectors, +x right, +y up)

enum Source : int {
    SOURCE_AUTO = 0,      // PICO tracking service (same as SOURCE_PICO; kept for saved settings)
    SOURCE_PICO = 1,      // PICO tracking service shared memory
    SOURCE_TEST = 3,      // synthetic animation, for verifying the OSC path in VRChat
};

// ---------------------------------------------------------------------------
// ARKit standard index enum (the order used in the mapping table)
// ---------------------------------------------------------------------------
enum Ak : int {
    eyeBlinkLeft, eyeLookDownLeft, eyeLookInLeft, eyeLookOutLeft, eyeLookUpLeft, eyeSquintLeft, eyeWideLeft,
    eyeBlinkRight, eyeLookDownRight, eyeLookInRight, eyeLookOutRight, eyeLookUpRight, eyeSquintRight, eyeWideRight,
    jawForward, jawLeft, jawRight, jawOpen, mouthClose, mouthFunnel, mouthPucker, mouthLeft, mouthRight,
    mouthSmileLeft, mouthSmileRight, mouthFrownLeft, mouthFrownRight, mouthDimpleLeft, mouthDimpleRight,
    mouthStretchLeft, mouthStretchRight, mouthRollLower, mouthRollUpper, mouthShrugLower, mouthShrugUpper,
    mouthPressLeft, mouthPressRight, mouthLowerDownLeft, mouthLowerDownRight, mouthUpperUpLeft, mouthUpperUpRight,
    browDownLeft, browDownRight, browInnerUp, browOuterUpLeft, browOuterUpRight, cheekPuff, cheekSquintLeft,
    cheekSquintRight, noseSneerLeft, noseSneerRight, tongueOut,
};
static_assert(tongueOut == 51, "ARKit enum must have 52 entries");

// ---------------------------------------------------------------------------
// VRChat Unified Expressions order — creators.vrchat.com/avatars/face-tracking/osc
// ---------------------------------------------------------------------------
enum Ue : int {
    EyeLookOutRight, EyeLookInRight, EyeLookUpRight, EyeLookDownRight,
    EyeLookOutLeft, EyeLookInLeft, EyeLookUpLeft, EyeLookDownLeft,
    EyeClosedRight, EyeClosedLeft, EyeDilationRight, EyeDilationLeft, EyeConstrictRight, EyeConstrictLeft,
    EyeSquintRight, EyeSquintLeft, EyeWideRight, EyeWideLeft,
    BrowPinchRight, BrowPinchLeft, BrowLowererRight, BrowLowererLeft,
    BrowInnerUpRight, BrowInnerUpLeft, BrowOuterUpRight, BrowOuterUpLeft,
    NasalDilationRight, NasalDilationLeft, NasalConstrictRight, NasalConstrictLeft,
    CheekSquintRight, CheekSquintLeft, CheekPuffRight, CheekPuffLeft, CheekSuckRight, CheekSuckLeft,
    JawOpen, JawRight, JawLeft, JawForward, JawBackward, JawClench, JawMandibleRaise,
    MouthClosed,
    LipSuckUpperRight, LipSuckUpperLeft, LipSuckLowerRight, LipSuckLowerLeft, LipSuckCornerRight, LipSuckCornerLeft,
    LipFunnelUpperRight, LipFunnelUpperLeft, LipFunnelLowerRight, LipFunnelLowerLeft,
    LipPuckerUpperRight, LipPuckerUpperLeft, LipPuckerLowerRight, LipPuckerLowerLeft,
    MouthUpperUpRight, MouthUpperUpLeft, MouthUpperDeepenRight, MouthUpperDeepenLeft,
    NoseSneerRight, NoseSneerLeft,
    MouthLowerDownRight, MouthLowerDownLeft,
    MouthUpperRight, MouthUpperLeft, MouthLowerRight, MouthLowerLeft,
    MouthCornerPullRight, MouthCornerPullLeft, MouthCornerSlantRight, MouthCornerSlantLeft,
    MouthFrownRight, MouthFrownLeft, MouthStretchRight, MouthStretchLeft,
    MouthDimpleRight, MouthDimpleLeft, MouthRaiserUpper, MouthRaiserLower,
    MouthPressRight, MouthPressLeft, MouthTightenerRight, MouthTightenerLeft,
    TongueOut, TongueUp, TongueDown, TongueRight, TongueLeft, TongueRoll, TongueBendDown, TongueCurlUp,
    TongueSquish, TongueFlat, TongueTwistRight, TongueTwistLeft, SoftPalateClose,
    ThroatSwallow, NeckFlexRight, NeckFlexLeft,
    UE_COUNT
};
static_assert(UE_COUNT == kUeCount, "Unified Expressions enum must have 102 entries");

// ---------------------------------------------------------------------------
// PICO source shape order -> ARKit standard order. kPicoToArkit[picoIndex] = arkitIndex.
// Order verified by PicoFacialBridge on PICO OS 5.13.7 (ExpressionCatalog.NAMES).
// ---------------------------------------------------------------------------
constexpr int kPicoToArkit[kArkitCount] = {
    eyeLookDownLeft,     // 0  EyeLookDownL
    noseSneerLeft,       // 1  NoseSneerL
    eyeLookInLeft,       // 2  EyeLookInL
    browInnerUp,         // 3  BrowInnerUp
    browDownRight,       // 4  BrowDownR
    mouthClose,          // 5  MouthClose
    mouthLowerDownRight, // 6  MouthLowerDownR
    jawOpen,             // 7  JawOpen
    mouthUpperUpRight,   // 8  MouthUpperUpR
    mouthShrugUpper,     // 9  MouthShrugUpper
    mouthFunnel,         // 10 MouthFunnel
    eyeLookInRight,      // 11 EyeLookInR
    eyeLookDownRight,    // 12 EyeLookDownR
    noseSneerRight,      // 13 NoseSneerR
    mouthRollUpper,      // 14 MouthRollUpper
    jawRight,            // 15 JawRight
    browDownLeft,        // 16 BrowDownL
    mouthShrugLower,     // 17 MouthShrugLower
    mouthRollLower,      // 18 MouthRollLower
    mouthSmileLeft,      // 19 MouthSmileL
    mouthPressLeft,      // 20 MouthPressL
    mouthSmileRight,     // 21 MouthSmileR
    mouthPressRight,     // 22 MouthPressR
    mouthDimpleRight,    // 23 MouthDimpleR
    mouthLeft,           // 24 MouthLeft
    jawForward,          // 25 JawForward
    eyeSquintLeft,       // 26 EyeSquintL
    mouthFrownLeft,      // 27 MouthFrownL
    eyeBlinkLeft,        // 28 EyeBlinkL
    cheekSquintLeft,     // 29 CheekSquintL
    browOuterUpLeft,     // 30 BrowOuterUpL
    eyeLookUpLeft,       // 31 EyeLookUpL
    jawLeft,             // 32 JawLeft
    mouthStretchLeft,    // 33 MouthStretchL
    mouthPucker,         // 34 MouthPucker
    eyeLookUpRight,      // 35 EyeLookUpR
    browOuterUpRight,    // 36 BrowOuterUpR
    cheekSquintRight,    // 37 CheekSquintR
    eyeBlinkRight,       // 38 EyeBlinkR
    mouthUpperUpLeft,    // 39 MouthUpperUpL
    mouthFrownRight,     // 40 MouthFrownR
    eyeSquintRight,      // 41 EyeSquintR
    mouthStretchRight,   // 42 MouthStretchR
    cheekPuff,           // 43 CheekPuff
    eyeLookOutLeft,      // 44 EyeLookOutL
    eyeLookOutRight,     // 45 EyeLookOutR
    eyeWideRight,        // 46 EyeWideR
    eyeWideLeft,         // 47 EyeWideL
    mouthRight,          // 48 MouthRight
    mouthDimpleLeft,     // 49 MouthDimpleL
    mouthLowerDownLeft,  // 50 MouthLowerDownL
    tongueOut,           // 51 TongueOut
};

// PICO shapes belonging to the eye region (gated by the eye-validity flag), PICO indices.
constexpr int kPicoEyeShapes[] = {0, 2, 3, 4, 11, 12, 16, 26, 28, 30, 31, 35, 36, 38, 41, 44, 45, 46, 47};

// ---------------------------------------------------------------------------
// PICO tracking service: Binder protocol and shared-memory facial ring
// ---------------------------------------------------------------------------
constexpr const char* kServiceName = "pxreyetrackingservice";
constexpr const char* kServiceDescriptor = "pvr.IEyeTrackingService";
constexpr transaction_code_t kTxStartAlgorithm = 6;
constexpr transaction_code_t kTxStopAlgorithm = 9;

// Ring header (little-endian int32): version, stride, capacity, published index, slot offset.
constexpr int kRingVersion = 1;
constexpr int kFaceSlotMin = 384;     // consumed prefix of each facial slot
// Facial slot layout (offsets in bytes):
constexpr int kFaceTimestamp = 0;     // int64
constexpr int kFaceShapes = 8;        // float[72], PICO order; 0..51 used
constexpr int kFaceRightInnerBrow = 216; // float, EPI1 extension (enhanced firmware only)
constexpr int kFaceEpiMagic = 228;    // int32 'EPI1'
constexpr int kFaceEpiVersion = 232;  // int32 1|2
constexpr int kFaceMouthTs = 240;     // int64, EPI1 v2
constexpr int kFaceRightTs = 248;     // int64, EPI1 v2
constexpr int kFaceEyeValid = 296;    // float == 1 when eye-region shapes are valid
constexpr int kFaceMouthValid = 300;  // float == 1 when face shapes are valid
constexpr int32_t kEpi1Magic = 0x31495045;

struct FaceRing {
    const uint8_t* base = nullptr;
    size_t size = 0;
    uint32_t stride = 0, capacity = 0, offset = 0;
};

// ---------------------------------------------------------------------------
// Global state (static storage only)
// ---------------------------------------------------------------------------
std::mutex gRingMutex;          // guards gRing against attach/detach while the loop reads it
FaceRing gRing;
alignas(16) uint8_t gFaceSample[kFaceSlotMin];
int64_t gLastSampleKey = 0;

int gSendFd = -1;
sockaddr_in gDest{};

alignas(16) uint8_t gPacket[kPacketSize];
alignas(16) float gArkit[kArkitCount];
alignas(16) float gGaze[kGazeFloats];
alignas(16) float gUe[kUeCount];
float gRightInnerBrow = -1.0f;   // >= 0 when the EPI1 extension supplies it


std::atomic<bool> gRunning{false};
std::atomic<bool> gLoopActive{false};   // true while runLoop() executes (shutdown waits for it)
std::atomic<int> gSource{SOURCE_AUTO};
std::atomic<bool> gRespectAvatarFlag{true};
// -1 = unknown (send), 0 = avatar without full face tracking (pause), 1 = supported
std::atomic<int> gAvatarFullFt{-1};

std::atomic<int64_t> gPacketsSent{0};
std::atomic<int64_t> gSendErrors{0};
std::atomic<int64_t> gFramesNoData{0};
std::atomic<int64_t> gFaceFrames{0};
std::atomic<int64_t> gLastFaceNs{0};
std::atomic<int> gFaceValidity{0};    // bit0 eye region valid, bit1 face valid (latest sample)
std::atomic<int> gActiveSource{-1};

// Gains (set from Kotlin). Face gain scales every expression except eye look and eyelids;
// eye gain scales eye look. EyeWide gets an extra x2 like UnifiedPicoModule's default.
std::atomic<float> gFaceGain{1.0f};
std::atomic<float> gEyeGain{1.0f};
std::atomic<float> gJawGain{1.3f};
std::atomic<bool> gSendEyeOsc{true};
constexpr float kEyeWideBaseGain = 2.0f;

// VRChat OSC eye tracking (/tracking/eye/*): moves eye bones, separate from the face blob.
constexpr int kEyeVecSize = 60;      // "/tracking/eye/LeftRightVec"(26)+pad->28, ",ffffff"->8, 6 floats
constexpr int kEyeClosedSize = 40;   // "/tracking/eye/EyesClosedAmount"(30)+pad->32, ",f"->4, 1 float
alignas(16) uint8_t gEyeVecPacket[kEyeVecSize];
alignas(16) uint8_t gEyeClosedPacket[kEyeClosedSize];

std::mutex gStatusMutex;        // gStatus is written by the session thread and read by the UI
char gStatus[256] = "PICO service not connected";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
inline int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

inline uint8_t toByte(float f) {
    if (!std::isfinite(f)) f = 0.0f;
    return static_cast<uint8_t>(std::clamp(f, 0.0f, 1.0f) * 255.0f + 0.5f);
}

template <typename T>
inline T loadShared(const uint8_t* p) {  // relaxed atomic load from the writer-shared mapping
    T v;
    __atomic_load(reinterpret_cast<const T*>(p), &v, __ATOMIC_RELAXED);
    return v;
}

template <typename T>
inline T readLocal(const uint8_t* p) {
    T v;
    std::memcpy(&v, p, sizeof(T));
    return v;
}

void setStatus(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void setStatus(const char* fmt, ...) {
    char text[sizeof(gStatus)];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(text, sizeof(text), fmt, ap);
    va_end(ap);
    // Keep it 7-bit ASCII: NewStringUTF() aborts the process on invalid modified UTF-8 (e.g. a message
    // truncated in the middle of a multi-byte character).
    for (char* c = text; *c; ++c) if (static_cast<unsigned char>(*c) >= 0x80) *c = '?';
    {
        std::lock_guard<std::mutex> lock(gStatusMutex);
        std::memcpy(gStatus, text, sizeof(gStatus));
    }
    LOGI("%s", text);
}

// Non-finite values (NaN from a corrupted preference or adb extra) would slip through std::clamp.
inline float finiteOr(float v, float fallback) { return std::isfinite(v) ? v : fallback; }

inline void putFloatBE(uint8_t* p, float f) {
    uint32_t v;
    std::memcpy(&v, &f, 4);
    v = htonl(v);
    std::memcpy(p, &v, 4);
}

void buildEyeHeaders() {
    std::memset(gEyeVecPacket, 0, sizeof(gEyeVecPacket));
    static const char kVec[] = "/tracking/eye/LeftRightVec";
    static_assert(sizeof(kVec) - 1 == 26, "vec address length");
    std::memcpy(gEyeVecPacket, kVec, sizeof(kVec) - 1);
    std::memcpy(gEyeVecPacket + 28, ",ffffff", 7);

    std::memset(gEyeClosedPacket, 0, sizeof(gEyeClosedPacket));
    static const char kClosed[] = "/tracking/eye/EyesClosedAmount";
    static_assert(sizeof(kClosed) - 1 == 30, "closed address length");
    std::memcpy(gEyeClosedPacket, kClosed, sizeof(kClosed) - 1);
    std::memcpy(gEyeClosedPacket + 32, ",f", 2);
}

void buildPacketHeader() {
    buildEyeHeaders();
    std::memset(gPacket, 0, sizeof(gPacket));
    static const char kAddr[] = "/tracking/face/v1";     // 17 chars + 3 NUL = 20
    static_assert(sizeof(kAddr) == 18, "address length");
    std::memcpy(gPacket, kAddr, sizeof(kAddr) - 1);
    gPacket[20] = ',';
    gPacket[21] = 'b';
    const uint32_t len = htonl(static_cast<uint32_t>(kUeCount));
    std::memcpy(gPacket + 24, &len, 4);
}

// ---------------------------------------------------------------------------
// Mapping: ARKit(52) + optional gaze -> Unified Expressions(102) -> packet
// eyeGaze (nullable): [lx, ly, lz, rx, ry, rz] unit direction vectors, +x = user's right, +y = up.
// When null, eye look values come from ARKit eyeLook* shapes (what PICO provides).
// ---------------------------------------------------------------------------
#include "filters.inc"
#include "calibration.inc"

void mapToUnified(const float* a, const float* eyeGaze) {
    float* u = gUe;
    std::memset(u, 0, sizeof(gUe));

    if (eyeGaze != nullptr) {
        const float lx = eyeGaze[0], ly = eyeGaze[1];
        const float rx = eyeGaze[3], ry = eyeGaze[4];
        u[EyeLookOutRight] = std::max(0.0f, rx);
        u[EyeLookInRight] = std::max(0.0f, -rx);
        u[EyeLookUpRight] = std::max(0.0f, ry);
        u[EyeLookDownRight] = std::max(0.0f, -ry);
        u[EyeLookOutLeft] = std::max(0.0f, -lx);
        u[EyeLookInLeft] = std::max(0.0f, lx);
        u[EyeLookUpLeft] = std::max(0.0f, ly);
        u[EyeLookDownLeft] = std::max(0.0f, -ly);
    } else {
        u[EyeLookOutRight] = a[eyeLookOutRight];
        u[EyeLookInRight] = a[eyeLookInRight];
        u[EyeLookUpRight] = a[eyeLookUpRight];
        u[EyeLookDownRight] = a[eyeLookDownRight];
        u[EyeLookOutLeft] = a[eyeLookOutLeft];
        u[EyeLookInLeft] = a[eyeLookInLeft];
        u[EyeLookUpLeft] = a[eyeLookUpLeft];
        u[EyeLookDownLeft] = a[eyeLookDownLeft];
    }

    u[EyeClosedRight] = a[eyeBlinkRight];
    u[EyeClosedLeft] = a[eyeBlinkLeft];
    u[EyeSquintRight] = a[eyeSquintRight];
    u[EyeSquintLeft] = a[eyeSquintLeft];
    u[EyeWideRight] = a[eyeWideRight];
    u[EyeWideLeft] = a[eyeWideLeft];

    u[BrowPinchRight] = a[browDownRight];
    u[BrowPinchLeft] = a[browDownLeft];
    u[BrowLowererRight] = a[browDownRight];
    u[BrowLowererLeft] = a[browDownLeft];
    u[BrowInnerUpRight] = gRightInnerBrow >= 0.0f ? gRightInnerBrow : a[browInnerUp];
    u[BrowInnerUpLeft] = a[browInnerUp];
    u[BrowOuterUpRight] = a[browOuterUpRight];
    u[BrowOuterUpLeft] = a[browOuterUpLeft];

    u[CheekSquintRight] = a[cheekSquintRight];
    u[CheekSquintLeft] = a[cheekSquintLeft];
    u[CheekPuffRight] = a[cheekPuff];
    u[CheekPuffLeft] = a[cheekPuff];

    u[JawOpen] = a[jawOpen];
    u[JawRight] = a[jawRight];
    u[JawLeft] = a[jawLeft];
    u[JawForward] = a[jawForward];

    u[MouthClosed] = a[mouthClose];

    u[LipSuckUpperRight] = a[mouthRollUpper];
    u[LipSuckUpperLeft] = a[mouthRollUpper];
    u[LipSuckLowerRight] = a[mouthRollLower];
    u[LipSuckLowerLeft] = a[mouthRollLower];

    u[LipFunnelUpperRight] = a[mouthFunnel];
    u[LipFunnelUpperLeft] = a[mouthFunnel];
    u[LipFunnelLowerRight] = a[mouthFunnel];
    u[LipFunnelLowerLeft] = a[mouthFunnel];

    u[LipPuckerUpperRight] = a[mouthPucker];
    u[LipPuckerUpperLeft] = a[mouthPucker];
    u[LipPuckerLowerRight] = a[mouthPucker];
    u[LipPuckerLowerLeft] = a[mouthPucker];

    u[MouthUpperUpRight] = a[mouthUpperUpRight];
    u[MouthUpperUpLeft] = a[mouthUpperUpLeft];
    u[MouthUpperDeepenRight] = a[mouthUpperUpRight];
    u[MouthUpperDeepenLeft] = a[mouthUpperUpLeft];

    u[NoseSneerRight] = a[noseSneerRight];
    u[NoseSneerLeft] = a[noseSneerLeft];

    u[MouthLowerDownRight] = a[mouthLowerDownRight];
    u[MouthLowerDownLeft] = a[mouthLowerDownLeft];

    u[MouthUpperRight] = a[mouthRight];
    u[MouthUpperLeft] = a[mouthLeft];
    u[MouthLowerRight] = a[mouthRight];
    u[MouthLowerLeft] = a[mouthLeft];

    u[MouthCornerPullRight] = a[mouthSmileRight];
    u[MouthCornerPullLeft] = a[mouthSmileLeft];
    u[MouthCornerSlantRight] = a[mouthSmileRight];
    u[MouthCornerSlantLeft] = a[mouthSmileLeft];

    u[MouthFrownRight] = a[mouthFrownRight];
    u[MouthFrownLeft] = a[mouthFrownLeft];
    u[MouthStretchRight] = a[mouthStretchRight];
    u[MouthStretchLeft] = a[mouthStretchLeft];
    u[MouthDimpleRight] = a[mouthDimpleRight];
    u[MouthDimpleLeft] = a[mouthDimpleLeft];
    u[MouthRaiserUpper] = a[mouthShrugUpper];
    u[MouthRaiserLower] = a[mouthShrugLower];
    u[MouthPressRight] = a[mouthPressRight];
    u[MouthPressLeft] = a[mouthPressLeft];

    u[TongueOut] = a[tongueOut];

    // Order: crosstalk rules (on raw context) -> deadzone -> gain -> clamp -> One Euro smoothing.
    const int64_t now = nowNs();
    const int flags = filt::gFlags.load(std::memory_order_relaxed);
    filt::applyRules(a, u, flags, now);
    filt::applyDeadzone(u);

    const float faceGain = gFaceGain.load(std::memory_order_relaxed);
    const float eyeGain = gEyeGain.load(std::memory_order_relaxed);
    for (int i = EyeLookOutRight; i <= EyeLookDownLeft; ++i) u[i] *= eyeGain;
    for (int i = EyeDilationRight; i < kUeCount; ++i) {
        if (i != JawOpen) u[i] *= faceGain;  // eyelids (8, 9) stay raw
    }
    u[JawOpen] *= gJawGain.load(std::memory_order_relaxed);
    u[EyeWideRight] *= kEyeWideBaseGain;
    u[EyeWideLeft] *= kEyeWideBaseGain;
    for (int i = 0; i < kUeCount; ++i) u[i] = std::isfinite(u[i]) ? std::clamp(u[i], 0.0f, 1.0f) : 0.0f;
    if (flags & filt::ONE_EURO) filt::applyOneEuro(u, now);

    uint8_t* blob = gPacket + kBlobOffset;
    for (int i = 0; i < kUeCount; ++i) blob[i] = toByte(u[i]);
}

bool sendPacket() {
    if (gSendFd < 0) return false;
    const ssize_t n = sendto(gSendFd, gPacket, kPacketSize, MSG_DONTWAIT,
                             reinterpret_cast<const sockaddr*>(&gDest), sizeof(gDest));
    if (n == kPacketSize) {
        gPacketsSent.fetch_add(1, std::memory_order_relaxed);
        return true;
    }
    gSendErrors.fetch_add(1, std::memory_order_relaxed);
    return false;
}

bool sendRaw(const uint8_t* data, int size) {
    if (gSendFd < 0) return false;
    const ssize_t n = sendto(gSendFd, data, static_cast<size_t>(size), MSG_DONTWAIT,
                             reinterpret_cast<const sockaddr*>(&gDest), sizeof(gDest));
    if (n == size) return true;
    gSendErrors.fetch_add(1, std::memory_order_relaxed);
    return false;
}

// Eye bones + eyelids via VRChat's OSC eye tracking, derived from the (gained) UE eye keys.
// Vectors are HMD-local Unity convention: +x right, +y up, +z forward, normalized.
void sendEyeOsc() {
    const float* u = gUe;
    const float lx = u[EyeLookInLeft] - u[EyeLookOutLeft];     // left eye looking right = inward
    const float rx = u[EyeLookOutRight] - u[EyeLookInRight];
    const float ly = u[EyeLookUpLeft] - u[EyeLookDownLeft];
    const float ry = u[EyeLookUpRight] - u[EyeLookDownRight];
    const float ln = 1.0f / std::sqrt(lx * lx + ly * ly + 1.0f);
    const float rn = 1.0f / std::sqrt(rx * rx + ry * ry + 1.0f);
    uint8_t* v = gEyeVecPacket + 36;
    putFloatBE(v + 0, lx * ln);  putFloatBE(v + 4, ly * ln);  putFloatBE(v + 8, ln);
    putFloatBE(v + 12, rx * rn); putFloatBE(v + 16, ry * rn); putFloatBE(v + 20, rn);
    sendRaw(gEyeVecPacket, kEyeVecSize);

    putFloatBE(gEyeClosedPacket + 36, 0.5f * (u[EyeClosedLeft] + u[EyeClosedRight]));
    sendRaw(gEyeClosedPacket, kEyeClosedSize);
}

#include "vrcft_params.inc"

// Native /tracking/face/v1 blob is skipped when VRChat reports the avatar lacks native face
// tracking; VRCFT v2 parameters and OSC eye tracking are still sent.
void mapAndSendOsc(const float* arkit52, const float* eyeGazeVectors, bool sendNativeBlob) {
    mapToUnified(calib::apply(arkit52), eyeGazeVectors);
    if (sendNativeBlob) sendPacket();
    if (gSendEyeOsc.load(std::memory_order_relaxed)) sendEyeOsc();
    v2::send(gUe, nowNs());
}

// ---------------------------------------------------------------------------
// OSC helpers
// ---------------------------------------------------------------------------
inline int oscPad4(int n) { return (n + 3) & ~3; }

// ---------------------------------------------------------------------------
// Shared-memory facial ring
// ---------------------------------------------------------------------------
// Validates the ring header. Returns nullptr when valid, otherwise a reason.
const char* validateRing(const uint8_t* base, size_t size, FaceRing* out) {
    if (size < 20) return "short header";
    const int32_t version = loadShared<int32_t>(base + 0);
    const uint32_t stride = loadShared<uint32_t>(base + 4);
    const uint32_t capacity = loadShared<uint32_t>(base + 8);
    const int32_t index = loadShared<int32_t>(base + 12);
    const uint32_t offset = loadShared<uint32_t>(base + 16);
    if (version != kRingVersion) return "unsupported ring version";
    if (stride < static_cast<uint32_t>(kFaceSlotMin) || stride > size) return "unsupported slot size";
    if (capacity < 2 || offset < 20) return "bad capacity/offset";
    if (static_cast<uint64_t>(offset) + static_cast<uint64_t>(stride) * capacity > size) return "ring exceeds mapping";
    if (index < -1 || index >= static_cast<int32_t>(capacity)) return "bad published index";
    out->base = base; out->size = size; out->stride = stride; out->capacity = capacity; out->offset = offset;
    return nullptr;
}

// Copies the latest published slot into gFaceSample. Caller holds gRingMutex.
bool copyLatestFace() {
    const FaceRing& r = gRing;
    if (r.base == nullptr) return false;
    for (int attempt = 0; attempt < 3; ++attempt) {
        const int32_t index = loadShared<int32_t>(r.base + 12);
        if (index < 0 || index >= static_cast<int32_t>(r.capacity)) return false;
        const uint8_t* slot = r.base + r.offset + static_cast<size_t>(r.stride) * index;
        const int64_t tsBefore = loadShared<int64_t>(slot + kFaceTimestamp);
        std::atomic_thread_fence(std::memory_order_acquire);
        std::memcpy(gFaceSample, slot, kFaceSlotMin);
        std::atomic_thread_fence(std::memory_order_acquire);
        // Seqlock-style check: the published index and slot timestamp must not have moved.
        if (loadShared<int32_t>(r.base + 12) == index &&
            loadShared<int64_t>(slot + kFaceTimestamp) == tsBefore &&
            readLocal<int64_t>(gFaceSample + kFaceTimestamp) == tsBefore) {
            return true;
        }
    }
    return false;
}

// Reads a fresh facial sample from the ring into gArkit. Returns true when a new sample arrived.
bool readPicoFrame() {
    std::lock_guard<std::mutex> lock(gRingMutex);
    if (!copyLatestFace()) return false;

    const uint8_t* s = gFaceSample;
    const bool epi1 = readLocal<int32_t>(s + kFaceEpiMagic) == kEpi1Magic;
    const int32_t epiVersion = epi1 ? readLocal<int32_t>(s + kFaceEpiVersion) : 0;
    int64_t key = readLocal<int64_t>(s + kFaceTimestamp);
    if (epiVersion == 2) {  // grouped clocks: left @0, mouth @240, right @248
        key = std::max(key, readLocal<int64_t>(s + kFaceMouthTs));
        key = std::max(key, readLocal<int64_t>(s + kFaceRightTs));
    }
    if (key <= 0 || key == gLastSampleKey) return false;
    gLastSampleKey = key;

    const bool eyeValid = readLocal<float>(s + kFaceEyeValid) == 1.0f;
    const bool mouthValid = readLocal<float>(s + kFaceMouthValid) == 1.0f;
    gFaceValidity.store((eyeValid ? 1 : 0) | (mouthValid ? 2 : 0), std::memory_order_relaxed);
    gFaceFrames.fetch_add(1, std::memory_order_relaxed);
    gLastFaceNs.store(nowNs(), std::memory_order_relaxed);
    if (!eyeValid && !mouthValid) return false;  // headset not worn / tracking lost: hold last frame

    for (int i = 0; i < kArkitCount; ++i) {
        const float v = readLocal<float>(s + kFaceShapes + i * 4);
        gArkit[kPicoToArkit[i]] = std::isfinite(v) ? v : 0.0f;
    }
    // Zero a group whose validity flag is off rather than forwarding stale/garbage values.
    static const auto isEye = [] {
        struct { bool v[kArkitCount] = {}; bool operator[](int i) const { return v[i]; } } t;
        for (int p : kPicoEyeShapes) t.v[kPicoToArkit[p]] = true;
        return t;
    }();
    for (int i = 0; i < kArkitCount; ++i) {
        if ((isEye[i] && !eyeValid) || (!isEye[i] && !mouthValid)) gArkit[i] = 0.0f;
    }
    if (eyeValid && mouthValid) calib::record(gArkit);
    gRightInnerBrow = -1.0f;
    if (epi1 && eyeValid) {
        const float rib = readLocal<float>(s + kFaceRightInnerBrow);
        if (std::isfinite(rib)) gRightInnerBrow = rib;
    }
    return true;
}

void fillTestPattern(int64_t tNs, const float** gazeOut) {
    const float t = static_cast<float>(tNs % 600000000000LL) * 1e-9f;
    std::memset(gArkit, 0, sizeof(gArkit));
    gRightInnerBrow = -1.0f;
    const float blink = std::fmod(t, 4.0f) < 0.15f ? 1.0f : 0.0f;
    gArkit[eyeBlinkLeft] = blink;
    gArkit[eyeBlinkRight] = blink;
    gArkit[jawOpen] = 0.5f + 0.5f * std::sin(t * 2.0f);
    gArkit[mouthSmileLeft] = 0.5f + 0.5f * std::sin(t * 0.7f);
    gArkit[mouthSmileRight] = gArkit[mouthSmileLeft];
    gArkit[browInnerUp] = 0.5f + 0.5f * std::sin(t * 1.3f);
    gArkit[tongueOut] = std::fmod(t, 10.0f) > 9.0f ? 1.0f : 0.0f;
    const float gx = 0.6f * std::sin(t * 0.9f);
    gGaze[0] = gx; gGaze[1] = 0.0f; gGaze[2] = -1.0f;
    gGaze[3] = gx; gGaze[4] = 0.0f; gGaze[5] = -1.0f;
    *gazeOut = gGaze;
}

// ---------------------------------------------------------------------------
// Binder: PICO eye-tracking service (NDK binder, API 29)
// ---------------------------------------------------------------------------
void* binderOnCreate(void* args) { return args; }
void binderOnDestroy(void*) {}
binder_status_t binderOnTransact(AIBinder*, transaction_code_t, const AParcel*, AParcel*) {
    return STATUS_UNKNOWN_TRANSACTION;
}
AIBinder_Class* eyeServiceClass() {
    static AIBinder_Class* clazz =
        AIBinder_Class_define(kServiceDescriptor, binderOnCreate, binderOnDestroy, binderOnTransact);
    return clazz;
}

// ---------------------------------------------------------------------------
// Socket setup
// ---------------------------------------------------------------------------
bool openSockets(int sendPort) {
    if (gSendFd < 0) {
        gSendFd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (gSendFd < 0) {
            LOGE("socket(send) failed: %s", strerror(errno));
            return false;
        }
    }
    std::memset(&gDest, 0, sizeof(gDest));
    gDest.sin_family = AF_INET;
    gDest.sin_port = htons(static_cast<uint16_t>(sendPort));
    gDest.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    buildPacketHeader();
    LOGI("OSC target 127.0.0.1:%d", sendPort);
    return true;
}

void closeSockets() {
    if (gSendFd >= 0) { close(gSendFd); gSendFd = -1; }
}

void detachRingLocked() {
    if (gRing.base != nullptr) {
        munmap(const_cast<uint8_t*>(gRing.base), gRing.size);
        LOGI("face ring unmapped");
    }
    gRing = FaceRing{};
    gLastSampleKey = 0;
}

// ---------------------------------------------------------------------------
// Main loop (runs on the Kotlin-created high-priority thread)
// ---------------------------------------------------------------------------
void runLoop(int hz) {
    hz = std::clamp(hz, 10, 120);
    const int64_t activePeriod = 1000000000LL / hz;
    constexpr int64_t kIdlePeriod = 100000000LL;  // 10 Hz while no frames arrive (tracking on standby)
    int64_t lastDataNs = 0;
    timespec next{};
    clock_gettime(CLOCK_MONOTONIC, &next);

    while (gRunning.load(std::memory_order_acquire)) {
        const int64_t t = nowNs();
        const float* gaze = nullptr;
        bool have = false;
        int used = -1;
        const int src = gSource.load(std::memory_order_relaxed);

        switch (src) {
            case SOURCE_PICO:
                have = readPicoFrame(); used = SOURCE_PICO; break;
            case SOURCE_TEST:
                fillTestPattern(t, &gaze); have = true; used = SOURCE_TEST; break;
            case SOURCE_AUTO:
            default:
                have = readPicoFrame(); used = SOURCE_PICO; break;
        }

        const bool paused = gRespectAvatarFlag.load(std::memory_order_relaxed) &&
                            gAvatarFullFt.load(std::memory_order_relaxed) == 0;
        if (have) lastDataNs = t;
        // Idle mode: no new data for 1 s → wake 10×/s instead of `hz`; the first new frame restores full rate.
        const int64_t period = (t - lastDataNs > 1000000000LL) ? kIdlePeriod : activePeriod;
        if (have) {
            gActiveSource.store(used, std::memory_order_relaxed);
            mapAndSendOsc(gArkit, gaze, !paused);
        } else {
            gFramesNoData.fetch_add(1, std::memory_order_relaxed);
        }

        next.tv_nsec += period;
        while (next.tv_nsec >= 1000000000L) { next.tv_nsec -= 1000000000L; next.tv_sec += 1; }
        const int64_t nextNs = static_cast<int64_t>(next.tv_sec) * 1000000000LL + next.tv_nsec;
        if (nowNs() - nextNs > 4 * period) clock_gettime(CLOCK_MONOTONIC, &next);  // resync after sleep
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, nullptr);
    }
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI surface — io.github.staperfeed.facebridge.NativeBridge
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeInit(JNIEnv*, jobject, jint sendPort) {
    return openSockets(sendPort) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeSetSource(JNIEnv*, jobject, jint source) {
    gSource.store(source == SOURCE_TEST ? SOURCE_TEST : SOURCE_PICO);
}

JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeSetRespectAvatarFlag(JNIEnv*, jobject, jboolean v) {
    gRespectAvatarFlag.store(v == JNI_TRUE);
}

// Configures VRCFT v2 parameter output for the current avatar (see vrcft_params.inc).
// fullFaceTracking: -1 unknown, 0 avatar lacks native FT (native blob paused), 1 supported.
JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeConfigureParams(JNIEnv* env, jobject, jstring prefix,
                                                                   jstring statusPrefix, jintArray flags,
                                                                   jboolean enabled, jint fullFaceTracking) {
    if (prefix == nullptr || statusPrefix == nullptr || flags == nullptr) return;
    const char* p = env->GetStringUTFChars(prefix, nullptr);
    const char* sp = env->GetStringUTFChars(statusPrefix, nullptr);
    if (p == nullptr || sp == nullptr) {  // OOM: exception pending
        if (p != nullptr) env->ReleaseStringUTFChars(prefix, p);
        if (sp != nullptr) env->ReleaseStringUTFChars(statusPrefix, sp);
        return;
    }
    const jint n = env->GetArrayLength(flags);
    jint local[v2::kSlotCount] = {};
    env->GetIntArrayRegion(flags, 0, std::min<jint>(n, v2::kSlotCount), local);
    v2::configure(p, sp, local, std::min<jint>(n, v2::kSlotCount), enabled == JNI_TRUE);
    env->ReleaseStringUTFChars(prefix, p);
    env->ReleaseStringUTFChars(statusPrefix, sp);
    gAvatarFullFt.store(std::clamp(static_cast<int>(fullFaceTracking), -1, 1));
}

JNIEXPORT jint JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeParamSlotCount(JNIEnv*, jobject) {
    return v2::kSlotCount;
}

JNIEXPORT jstring JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeParamSlotName(JNIEnv* env, jobject, jint slot) {
    if (slot < 0 || slot >= v2::kSlotCount) return nullptr;
    return env->NewStringUTF(v2::slotName(slot));
}

JNIEXPORT jboolean JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeSendBool(JNIEnv* env, jobject, jstring address, jboolean value) {
    uint8_t buf[256] = {};
    if (address == nullptr) return JNI_FALSE;
    const char* a = env->GetStringUTFChars(address, nullptr);
    if (a == nullptr) return JNI_FALSE;
    const int len = std::min(static_cast<int>(std::strlen(a)), 200);
    std::memcpy(buf, a, len);
    env->ReleaseStringUTFChars(address, a);
    int off = oscPad4(len + 1);
    buf[off] = ',';
    buf[off + 1] = value == JNI_TRUE ? 'T' : 'F';
    off += 4;
    return sendRaw(buf, off) ? JNI_TRUE : JNI_FALSE;
}

// Debug: /chatbox/input <text> T (send immediately, no keyboard). Not used in the hot loop.
JNIEXPORT jboolean JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeSendChatbox(JNIEnv* env, jobject, jstring text) {
    uint8_t buf[256] = {};
    const char* addr = "/chatbox/input";
    int off = 0;
    std::memcpy(buf, addr, std::strlen(addr));
    off = oscPad4(static_cast<int>(std::strlen(addr)) + 1);
    std::memcpy(buf + off, ",sT", 3);
    off += 4;
    if (text == nullptr) return JNI_FALSE;
    const char* c = env->GetStringUTFChars(text, nullptr);
    if (c == nullptr) return JNI_FALSE;
    const int len = std::min(static_cast<int>(std::strlen(c)), 140);
    std::memcpy(buf + off, c, len);
    env->ReleaseStringUTFChars(text, c);
    off += oscPad4(len + 1);
    LOGI("chatbox debug send (%d bytes)", off);
    return sendRaw(buf, off) ? JNI_TRUE : JNI_FALSE;
}

// Calibration: phase 1 neutral, 2 expressions, 0 cancel.
JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeCalibrationPhase(JNIEnv*, jobject, jint phase) {
    if (phase == calib::IDLE) calib::gPhase.store(calib::IDLE);  // pause, keep data
    else calib::beginPhase(phase);
    LOGI("calibration phase %d", phase);
}

// Latest raw frame (52 shapes, ARKit order) for the live preview face. UI thread, not hot path.
JNIEXPORT jboolean JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeGetLiveShapes(JNIEnv* env, jobject, jfloatArray out) {
    if (out == nullptr || env->GetArrayLength(out) < kArkitCount) return JNI_FALSE;
    const int64_t last = gLastFaceNs.load();
    if (last == 0 || nowNs() - last > 1000000000LL) return JNI_FALSE;
    float copy[kArkitCount];
    std::memcpy(copy, gArkit, sizeof(copy));  // benign race: preview only
    env->SetFloatArrayRegion(out, 0, kArkitCount, copy);
    return JNI_TRUE;
}

// Returns [lower(52), upper(52)] or null when the session had too little tracking data.
JNIEXPORT jfloatArray JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeCalibrationFinish(JNIEnv* env, jobject) {
    float lower[kArkitCount], upper[kArkitCount];
    if (!calib::finish(lower, upper)) { LOGW("calibration: not enough data"); return nullptr; }
    jfloatArray out = env->NewFloatArray(2 * kArkitCount);
    if (out == nullptr) return nullptr;
    env->SetFloatArrayRegion(out, 0, kArkitCount, lower);
    env->SetFloatArrayRegion(out, kArkitCount, kArkitCount, upper);
    return out;
}

JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeSetCalibrationRange(JNIEnv*, jobject, jfloat range) {
    calib::gRange.store(std::clamp(finiteOr(range, 0.5f), 0.0f, 1.0f));
}

JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeSetCalibration(JNIEnv* env, jobject, jfloatArray data,
                                                                 jboolean enabled) {
    float buf[2 * kArkitCount];
    for (int i = 0; i < kArkitCount; ++i) { buf[i] = 0.0f; buf[kArkitCount + i] = 1.0f; }
    if (data != nullptr && env->GetArrayLength(data) >= 2 * kArkitCount)
        env->GetFloatArrayRegion(data, 0, 2 * kArkitCount, buf);
    calib::set(buf, buf + kArkitCount, enabled == JNI_TRUE && data != nullptr);
    LOGI("calibration %s", enabled == JNI_TRUE && data != nullptr ? "on" : "off");
}

// Crosstalk rules bitmask (see filters.inc), deadzone 0..0.5, smoothing 0..1.
JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeSetFilters(JNIEnv*, jobject, jint flags, jfloat deadzone,
                                                             jfloat smoothing) {
    filt::gFlags.store(flags);
    filt::gDeadzone.store(std::clamp(finiteOr(deadzone, 0.08f), 0.0f, 0.5f));
    filt::gSmoothing.store(std::clamp(finiteOr(smoothing, 0.3f), 0.0f, 1.0f));
    LOGI("filters: flags=0x%x deadzone=%.2f smoothing=%.2f", flags, deadzone, smoothing);
}

JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeSetTuning(JNIEnv*, jobject, jfloat faceGain, jfloat eyeGain,
                                                            jboolean sendEyeOsc, jfloat jawGain) {
    gJawGain.store(std::clamp(finiteOr(jawGain, 1.3f), 0.0f, 5.0f));
    gFaceGain.store(std::clamp(finiteOr(faceGain, 1.0f), 0.0f, 5.0f));
    gEyeGain.store(std::clamp(finiteOr(eyeGain, 1.0f), 0.0f, 5.0f));
    gSendEyeOsc.store(sendEyeOsc == JNI_TRUE);
    LOGI("tuning: faceGain=%.2f jawGain=%.2f eyeGain=%.2f eyeOsc=%d", faceGain, jawGain, eyeGain, sendEyeOsc == JNI_TRUE);
}

// Diagnostic snapshot of the ring header and latest slot (not used in the hot loop).
JNIEXPORT jstring JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeRingDebug(JNIEnv* env, jobject) {
    char buf[384];
    std::lock_guard<std::mutex> lock(gRingMutex);
    const FaceRing& r = gRing;
    if (r.base == nullptr) {
        snprintf(buf, sizeof(buf), "ring not mapped; sent=%lld source=%d avatarFT=%d",
                 static_cast<long long>(gPacketsSent.load()), gActiveSource.load(), gAvatarFullFt.load());
        return env->NewStringUTF(buf);
    }
    const int32_t index = loadShared<int32_t>(r.base + 12);
    long long ts = 0;
    float eyeValid = 0, faceValid = 0;
    if (index >= 0 && index < static_cast<int32_t>(r.capacity)) {
        const uint8_t* slot = r.base + r.offset + static_cast<size_t>(r.stride) * index;
        ts = loadShared<int64_t>(slot + kFaceTimestamp);
        eyeValid = loadShared<float>(slot + kFaceEyeValid);
        faceValid = loadShared<float>(slot + kFaceMouthValid);
    }
    timespec boot{};
    clock_gettime(CLOCK_BOOTTIME, &boot);
    snprintf(buf, sizeof(buf), "ring index=%d ageMs=%lld valid(eye=%.0f face=%.0f) frames=%lld sent=%lld source=%d avatarFT=%d v2=%d/%lld | jaw=%u blinkL=%u blinkR=%u smileL=%u smileR=%u lookOutL=%u lookOutR=%u",
             index, ts > 0 ? (static_cast<long long>(boot.tv_sec) * 1000000000LL + boot.tv_nsec - ts) / 1000000LL : -1LL,
             eyeValid, faceValid,
             static_cast<long long>(gFaceFrames.load()), static_cast<long long>(gPacketsSent.load()),
             gActiveSource.load(), gAvatarFullFt.load(), v2::gActiveSlots.load(),
             static_cast<long long>(v2::gMessagesSent.load()),
             gPacket[kBlobOffset + JawOpen], gPacket[kBlobOffset + EyeClosedLeft], gPacket[kBlobOffset + EyeClosedRight],
             gPacket[kBlobOffset + MouthCornerPullLeft], gPacket[kBlobOffset + MouthCornerPullRight],
             gPacket[kBlobOffset + EyeLookOutLeft], gPacket[kBlobOffset + EyeLookOutRight]);
    return env->NewStringUTF(buf);
}

// Looks up "pxreyetrackingservice" via the NDK service manager (APEX symbol on Android 10,
// resolved optionally). Returns null when unavailable; Kotlin then falls back to ServiceManager.
JNIEXPORT jobject JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeLookupService(JNIEnv* env, jobject) {
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (lib == nullptr) { LOGW("dlopen(libbinder_ndk) failed: %s", dlerror()); return nullptr; }
    using CheckService = AIBinder* (*)(const char*);
    auto check = reinterpret_cast<CheckService>(dlsym(lib, "AServiceManager_checkService"));
    jobject result = nullptr;
    if (check != nullptr) {
        AIBinder* binder = check(kServiceName);
        LOGI("AServiceManager_checkService(%s) = %p", kServiceName, binder);
        if (binder != nullptr) {
            result = AIBinder_toJavaBinder(env, binder);
            AIBinder_decStrong(binder);
        }
    } else {
        LOGW("AServiceManager_checkService unavailable: %s", dlerror());
    }
    dlclose(lib);
    return result;
}

// StartAlgorithm (code 6: int32 5, string "12", int32 1000) or StopAlgorithm (code 9: int32 5, int32 12).
// out: [transportStatus, binderExceptionCode, serviceStatus]; all three are 0 on success.
JNIEXPORT jintArray JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeAlgorithm(JNIEnv* env, jobject, jobject javaBinder, jboolean start) {
    jint result[3] = {STATUS_BAD_VALUE, INT_MIN, INT_MIN};
    AIBinder* binder = javaBinder != nullptr ? AIBinder_fromJavaBinder(env, javaBinder) : nullptr;
    AParcel* request = nullptr;
    AParcel* reply = nullptr;
    if (binder != nullptr && AIBinder_associateClass(binder, eyeServiceClass())) {
        result[0] = AIBinder_prepareTransaction(binder, &request);  // writes the interface token
        if (result[0] == STATUS_OK) result[0] = AParcel_writeInt32(request, 5);
        if (result[0] == STATUS_OK)
            result[0] = start ? AParcel_writeString(request, "12", 2) : AParcel_writeInt32(request, 12);
        if (result[0] == STATUS_OK && start) result[0] = AParcel_writeInt32(request, 1000);
        if (result[0] == STATUS_OK)
            result[0] = AIBinder_transact(binder, start ? kTxStartAlgorithm : kTxStopAlgorithm, &request, &reply, 0);
        if (result[0] == STATUS_OK) {
            binder_status_t parse = AParcel_readInt32(reply, &result[1]);
            if (parse == STATUS_OK && result[1] == 0) AParcel_readInt32(reply, &result[2]);
        }
        LOGI("%s algorithm: transport=%d binder=%d service=%d", start ? "start" : "stop",
             result[0], result[1], result[2]);
    } else {
        LOGE("AIBinder_associateClass(%s) failed", kServiceDescriptor);
    }
    if (request != nullptr) AParcel_delete(request);
    if (reply != nullptr) AParcel_delete(reply);
    if (binder != nullptr) AIBinder_decStrong(binder);
    jintArray array = env->NewIntArray(3);
    if (array != nullptr) env->SetIntArrayRegion(array, 0, 3, result);
    return array;
}

// mmaps the facial ring read-only. The fd can be closed by the caller afterwards.
// Returns null on success, or an error description.
JNIEXPORT jstring JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeAttachFaceRing(JNIEnv* env, jobject, jint fd, jint size) {
    char err[160];
    struct stat st{};
    if (fstat(fd, &st) != 0) {
        snprintf(err, sizeof(err), "fstat: %s", strerror(errno));
        return env->NewStringUTF(err);
    }
    if (size < 20 || size > 16 * 1024 * 1024 || (st.st_size > 0 && st.st_size < size)) {
        snprintf(err, sizeof(err), "bad ring size %d (fstat %lld)", size, static_cast<long long>(st.st_size));
        return env->NewStringUTF(err);
    }
    void* mem = mmap(nullptr, static_cast<size_t>(size), PROT_READ, MAP_SHARED, fd, 0);
    if (mem == MAP_FAILED) {
        snprintf(err, sizeof(err), "mmap: %s", strerror(errno));
        return env->NewStringUTF(err);
    }
    FaceRing ring;
    const char* why = validateRing(static_cast<const uint8_t*>(mem), static_cast<size_t>(size), &ring);
    if (why != nullptr) {
        snprintf(err, sizeof(err), "face ring rejected: %s (stride=%u)", why,
                 loadShared<uint32_t>(static_cast<const uint8_t*>(mem) + 4));
        munmap(mem, static_cast<size_t>(size));
        return env->NewStringUTF(err);
    }
    {
        std::lock_guard<std::mutex> lock(gRingMutex);
        detachRingLocked();
        gRing = ring;
    }
    setStatus("PICO face ring: size=%d stride=%u capacity=%u", size, ring.stride, ring.capacity);
    return nullptr;
}

JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeDetachFaceRing(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gRingMutex);
    detachRingLocked();
}

JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeSetStatus(JNIEnv* env, jobject, jstring text) {
    const char* c = text != nullptr ? env->GetStringUTFChars(text, nullptr) : nullptr;
    setStatus("%s", c != nullptr ? c : "");
    if (c != nullptr) env->ReleaseStringUTFChars(text, c);
}

// Blocks until nativeStop(). Call from a dedicated thread.
JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeRunLoop(JNIEnv*, jobject, jint hz) {
    bool expected = false;
    if (!gRunning.compare_exchange_strong(expected, true)) return;
    gLoopActive.store(true, std::memory_order_release);
    LOGI("loop start @ %d Hz", hz);
    runLoop(hz);
    LOGI("loop exit");
    gLoopActive.store(false, std::memory_order_release);
}

JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeStop(JNIEnv*, jobject) {
    gRunning.store(false, std::memory_order_release);
}

JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeShutdown(JNIEnv*, jobject) {
    gRunning.store(false, std::memory_order_release);
    // Closing the socket under a running loop could make it sendto() a recycled fd number.
    for (int i = 0; i < 100 && gLoopActive.load(std::memory_order_acquire); ++i) usleep(10000);
    if (gLoopActive.load(std::memory_order_acquire)) {
        LOGW("shutdown: loop still running, keeping the socket open");
    } else {
        closeSockets();
    }
    std::lock_guard<std::mutex> lock(gRingMutex);
    detachRingLocked();
}

// out[0]=sent, [1]=sendErrors, [2]=noDataTicks, [3]=avatarFullFt (-1/0/1), [4]=activeSource,
// [5]=ringAttached, [6]=faceFrames, [7]=msSinceFaceFrame (-1 never), [8]=faceValidity bits
JNIEXPORT void JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeGetStats(JNIEnv* env, jobject, jlongArray out) {
    constexpr int kStats = 9;
    if (out == nullptr || env->GetArrayLength(out) < kStats) return;
    const int64_t lastFace = gLastFaceNs.load();
    bool attached;
    {
        std::lock_guard<std::mutex> lock(gRingMutex);
        attached = gRing.base != nullptr;
    }
    jlong v[kStats] = {
        gPacketsSent.load(), gSendErrors.load(), gFramesNoData.load(),
        gAvatarFullFt.load(), gActiveSource.load(), attached ? 1 : 0,
        gFaceFrames.load(), lastFace == 0 ? -1 : (nowNs() - lastFace) / 1000000LL, gFaceValidity.load(),
    };
    env->SetLongArrayRegion(out, 0, kStats, v);
}

JNIEXPORT jstring JNICALL
Java_io_github_staperfeed_facebridge_NativeBridge_nativeBackendInfo(JNIEnv* env, jobject) {
    char copy[sizeof(gStatus)];
    {
        std::lock_guard<std::mutex> lock(gStatusMutex);
        std::memcpy(copy, gStatus, sizeof(copy));
    }
    copy[sizeof(copy) - 1] = '\0';
    return env->NewStringUTF(copy);
}

}  // extern "C"
