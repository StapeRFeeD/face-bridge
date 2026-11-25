// vrcft_params.inc — VRCFaceTracking v2 avatar parameters (/avatar/parameters/<prefix>v2/<Name>).
// Included by native-lib.cpp after the Unified Expressions enum, gUe, sendRaw() and putFloatBE().
//
// Avatars built for VRCFaceTracking (not VRChat's native face tracking) animate from these
// parameters. VRCFT exposes every Unified Expression by name plus "combined" parameters
// (EyeLeftX, EyeLidLeft, SmileFrownLeft, JawX, ...). Remote sync usually uses bit-packed bool
// copies: <Name>1, <Name>2, <Name>4 (... up to 128) and <Name>Negative for signed values.
//
// Kotlin reads the current avatar's parameter list through VRChat's OSCQuery server and calls
// nativeConfigureParams(); only parameters the avatar has are sent. Hot path: no allocation.

namespace v2 {

// Unified Expression names, in Ue enum order (each is also a v2 parameter).
const char* const kUeNames[kUeCount] = {
    "EyeLookOutRight", "EyeLookInRight", "EyeLookUpRight", "EyeLookDownRight",
    "EyeLookOutLeft", "EyeLookInLeft", "EyeLookUpLeft", "EyeLookDownLeft",
    "EyeClosedRight", "EyeClosedLeft", "EyeDilationRight", "EyeDilationLeft", "EyeConstrictRight", "EyeConstrictLeft",
    "EyeSquintRight", "EyeSquintLeft", "EyeWideRight", "EyeWideLeft",
    "BrowPinchRight", "BrowPinchLeft", "BrowLowererRight", "BrowLowererLeft",
    "BrowInnerUpRight", "BrowInnerUpLeft", "BrowOuterUpRight", "BrowOuterUpLeft",
    "NasalDilationRight", "NasalDilationLeft", "NasalConstrictRight", "NasalConstrictLeft",
    "CheekSquintRight", "CheekSquintLeft", "CheekPuffRight", "CheekPuffLeft", "CheekSuckRight", "CheekSuckLeft",
    "JawOpen", "JawRight", "JawLeft", "JawForward", "JawBackward", "JawClench", "JawMandibleRaise",
    "MouthClosed",
    "LipSuckUpperRight", "LipSuckUpperLeft", "LipSuckLowerRight", "LipSuckLowerLeft", "LipSuckCornerRight", "LipSuckCornerLeft",
    "LipFunnelUpperRight", "LipFunnelUpperLeft", "LipFunnelLowerRight", "LipFunnelLowerLeft",
    "LipPuckerUpperRight", "LipPuckerUpperLeft", "LipPuckerLowerRight", "LipPuckerLowerLeft",
    "MouthUpperUpRight", "MouthUpperUpLeft", "MouthUpperDeepenRight", "MouthUpperDeepenLeft",
    "NoseSneerRight", "NoseSneerLeft",
    "MouthLowerDownRight", "MouthLowerDownLeft",
    "MouthUpperRight", "MouthUpperLeft", "MouthLowerRight", "MouthLowerLeft",
    "MouthCornerPullRight", "MouthCornerPullLeft", "MouthCornerSlantRight", "MouthCornerSlantLeft",
    "MouthFrownRight", "MouthFrownLeft", "MouthStretchRight", "MouthStretchLeft",
    "MouthDimpleRight", "MouthDimpleLeft", "MouthRaiserUpper", "MouthRaiserLower",
    "MouthPressRight", "MouthPressLeft", "MouthTightenerRight", "MouthTightenerLeft",
    "TongueOut", "TongueUp", "TongueDown", "TongueRight", "TongueLeft", "TongueRoll", "TongueBendDown", "TongueCurlUp",
    "TongueSquish", "TongueFlat", "TongueTwistRight", "TongueTwistLeft", "SoftPalateClose",
    "ThroatSwallow", "NeckFlexRight", "NeckFlexLeft",
};

// Combined VRCFT v2 parameters (those that are not plain Unified Expression names).
enum Combined : int {
    EyeLeftX, EyeRightX, EyeY, EyeLidLeft, EyeLidRight, EyeLid, EyeSquint, EyeWide, PupilDilation,
    BrowExpressionLeft, BrowExpressionRight, BrowUp, BrowDown, BrowInnerUp, BrowOuterUp,
    NoseSneer, CheekPuffSuckLeft, CheekPuffSuckRight, CheekPuffSuck, CheekSquint,
    JawX, JawZ, LipSuckUpper, LipSuckLower, LipSuck, LipFunnel, LipPucker,
    MouthUpperUp, MouthLowerDown, MouthOpen, MouthX, MouthUpperX, MouthLowerX,
    SmileFrownRight, SmileFrownLeft, SmileFrown, SmileSadRight, SmileSadLeft, SmileSad,
    MouthSmileRight, MouthSmileLeft, MouthSadRight, MouthSadLeft,
    MouthStretch, MouthTightener, MouthPress, MouthDimple,
    TongueX, TongueY, TongueArchY, TongueShape,
    COMBINED_COUNT
};
const char* const kCombinedNames[COMBINED_COUNT] = {
    "EyeLeftX", "EyeRightX", "EyeY", "EyeLidLeft", "EyeLidRight", "EyeLid", "EyeSquint", "EyeWide", "PupilDilation",
    "BrowExpressionLeft", "BrowExpressionRight", "BrowUp", "BrowDown", "BrowInnerUp", "BrowOuterUp",
    "NoseSneer", "CheekPuffSuckLeft", "CheekPuffSuckRight", "CheekPuffSuck", "CheekSquint",
    "JawX", "JawZ", "LipSuckUpper", "LipSuckLower", "LipSuck", "LipFunnel", "LipPucker",
    "MouthUpperUp", "MouthLowerDown", "MouthOpen", "MouthX", "MouthUpperX", "MouthLowerX",
    "SmileFrownRight", "SmileFrownLeft", "SmileFrown", "SmileSadRight", "SmileSadLeft", "SmileSad",
    "MouthSmileRight", "MouthSmileLeft", "MouthSadRight", "MouthSadLeft",
    "MouthStretch", "MouthTightener", "MouthPress", "MouthDimple",
    "TongueX", "TongueY", "TongueArchY", "TongueShape",
};

// Slots: [0, kUeCount) Unified Expressions, then combined, then two status bools.
constexpr int kSlotEyeActive = kUeCount + COMBINED_COUNT;   // "EyeTrackingActive" (no v2/ prefix)
constexpr int kSlotLipActive = kSlotEyeActive + 1;           // "LipTrackingActive"
constexpr int kSlotCount = kSlotLipActive + 1;

inline const char* slotName(int s) {
    if (s < kUeCount) return kUeNames[s];
    if (s < kSlotEyeActive) return kCombinedNames[s - kUeCount];
    return s == kSlotEyeActive ? "EyeTrackingActive" : "LipTrackingActive";
}

// Per-slot config flags (from Kotlin): bit0 float present, bits1..4 = number of binary bits (0..8),
// bit5 Negative present. Status slots: bit0 = bool parameter present.
constexpr int kMaxBits = 8;
struct SlotCfg {
    bool hasFloat = false;
    int bits = 0;
    bool hasNeg = false;
    // Offsets into gAddrPool of padded addresses: float, bit[i], negative.
    int floatAddr = -1, floatLen = 0;
    int bitAddr[kMaxBits] = {};
    int bitLen[kMaxBits] = {};
    int negAddr = -1, negLen = 0;
};

std::mutex gMutex;           // guards configuration against the loop
SlotCfg gCfg[kSlotCount];
bool gEnabled = true;        // false = avatar has no VRCFT parameters
alignas(16) char gAddrPool[48 * 1024];
int gPoolUsed = 0;

float gValue[kSlotCount];
float gLastFloat[kSlotCount];
int gLastBits[kSlotCount];
int64_t gLastFullNs = 0;

alignas(16) uint8_t gBundle[4096];
int gBundleLen = 0;
std::atomic<int64_t> gMessagesSent{0};
std::atomic<int> gActiveSlots{0};

inline int pad4(int n) { return (n + 3) & ~3; }

// Appends a NUL-padded OSC address to the pool; returns offset (or -1 when full).
int poolAddr(const char* prefix, const char* name, const char* suffix, int* lenOut) {
    char tmp[160];
    const int n = snprintf(tmp, sizeof(tmp), "%s%s%s", prefix, name, suffix);
    if (n <= 0 || n >= static_cast<int>(sizeof(tmp))) return -1;
    const int padded = pad4(n + 1);
    if (gPoolUsed + padded > static_cast<int>(sizeof(gAddrPool))) return -1;
    const int off = gPoolUsed;
    std::memset(gAddrPool + off, 0, padded);
    std::memcpy(gAddrPool + off, tmp, n);
    gPoolUsed += padded;
    *lenOut = padded;
    return off;
}

// Called from JNI (allocation allowed here). flags.size() == kSlotCount.
void configure(const char* prefix, const char* statusPrefix, const int* flags, int count, bool enabled) {
    std::lock_guard<std::mutex> lock(gMutex);
    gPoolUsed = 0;
    int active = 0;
    for (int s = 0; s < kSlotCount; ++s) {
        SlotCfg c;
        const int f = s < count ? flags[s] : 0;
        const bool status = s >= kSlotEyeActive;
        c.hasFloat = (f & 1) != 0;
        c.bits = status ? 0 : std::min((f >> 1) & 0xF, kMaxBits);
        c.hasNeg = !status && (f & 0x20) != 0;
        const char* pre = status ? statusPrefix : prefix;
        if (c.hasFloat) c.floatAddr = poolAddr(pre, slotName(s), "", &c.floatLen);
        for (int b = 0; b < c.bits; ++b) {
            char suffix[8];
            snprintf(suffix, sizeof(suffix), "%d", 1 << b);
            c.bitAddr[b] = poolAddr(pre, slotName(s), suffix, &c.bitLen[b]);
        }
        if (c.hasNeg) c.negAddr = poolAddr(pre, slotName(s), "Negative", &c.negLen);
        if (c.hasFloat || c.bits || c.hasNeg) ++active;
        gCfg[s] = c;
        gLastFloat[s] = -100.0f;
        gLastBits[s] = -1;
    }
    gEnabled = enabled;
    gActiveSlots.store(active);
    gLastFullNs = 0;
    LOGI("VRCFT v2 params: %d active, prefix=%s, pool=%d bytes, enabled=%d", active, prefix, gPoolUsed, enabled);
}

inline float avg2(float a, float b) { return 0.5f * (a + b); }

// Derives every slot value from the (gained, clamped) Unified Expressions in u.
void compute(const float* u) {
    float* v = gValue;
    for (int i = 0; i < kUeCount; ++i) v[i] = u[i];
    float* c = v + kUeCount;
    // Gaze: x positive = looking right (left eye looks right = inward), y positive = up.
    c[EyeLeftX] = u[EyeLookInLeft] - u[EyeLookOutLeft];
    c[EyeRightX] = u[EyeLookOutRight] - u[EyeLookInRight];
    c[EyeY] = avg2(u[EyeLookUpLeft] - u[EyeLookDownLeft], u[EyeLookUpRight] - u[EyeLookDownRight]);
    // EyeLid: 0 closed, 0.75 normally open, 1 wide (VRCFT convention).
    c[EyeLidLeft] = (1.0f - u[EyeClosedLeft]) * 0.75f + u[EyeWideLeft] * 0.25f;
    c[EyeLidRight] = (1.0f - u[EyeClosedRight]) * 0.75f + u[EyeWideRight] * 0.25f;
    c[EyeLid] = avg2(c[EyeLidLeft], c[EyeLidRight]);
    c[EyeSquint] = avg2(u[EyeSquintLeft], u[EyeSquintRight]);
    c[EyeWide] = avg2(u[EyeWideLeft], u[EyeWideRight]);
    c[PupilDilation] = 0.5f;  // PICO 4 Pro (rootless) has no pupil measurement
    const float browUpL = avg2(u[BrowInnerUpLeft], u[BrowOuterUpLeft]);
    const float browUpR = avg2(u[BrowInnerUpRight], u[BrowOuterUpRight]);
    c[BrowExpressionLeft] = browUpL - u[BrowLowererLeft];
    c[BrowExpressionRight] = browUpR - u[BrowLowererRight];
    c[BrowUp] = avg2(browUpL, browUpR);
    c[BrowDown] = avg2(u[BrowLowererLeft], u[BrowLowererRight]);
    c[BrowInnerUp] = avg2(u[BrowInnerUpLeft], u[BrowInnerUpRight]);
    c[BrowOuterUp] = avg2(u[BrowOuterUpLeft], u[BrowOuterUpRight]);
    c[NoseSneer] = avg2(u[NoseSneerLeft], u[NoseSneerRight]);
    c[CheekPuffSuckLeft] = u[CheekPuffLeft] - u[CheekSuckLeft];
    c[CheekPuffSuckRight] = u[CheekPuffRight] - u[CheekSuckRight];
    c[CheekPuffSuck] = avg2(c[CheekPuffSuckLeft], c[CheekPuffSuckRight]);
    c[CheekSquint] = avg2(u[CheekSquintLeft], u[CheekSquintRight]);
    c[JawX] = u[JawRight] - u[JawLeft];
    c[JawZ] = u[JawForward] - u[JawBackward];
    c[LipSuckUpper] = avg2(u[LipSuckUpperLeft], u[LipSuckUpperRight]);
    c[LipSuckLower] = avg2(u[LipSuckLowerLeft], u[LipSuckLowerRight]);
    c[LipSuck] = avg2(c[LipSuckUpper], c[LipSuckLower]);
    c[LipFunnel] = 0.25f * (u[LipFunnelUpperLeft] + u[LipFunnelUpperRight] + u[LipFunnelLowerLeft] + u[LipFunnelLowerRight]);
    c[LipPucker] = 0.25f * (u[LipPuckerUpperLeft] + u[LipPuckerUpperRight] + u[LipPuckerLowerLeft] + u[LipPuckerLowerRight]);
    c[MouthUpperUp] = avg2(u[MouthUpperUpLeft], u[MouthUpperUpRight]);
    c[MouthLowerDown] = avg2(u[MouthLowerDownLeft], u[MouthLowerDownRight]);
    c[MouthOpen] = std::clamp(avg2(c[MouthUpperUp], c[MouthLowerDown]), 0.0f, 1.0f);
    c[MouthUpperX] = u[MouthUpperRight] - u[MouthUpperLeft];
    c[MouthLowerX] = u[MouthLowerRight] - u[MouthLowerLeft];
    c[MouthX] = avg2(c[MouthUpperX], c[MouthLowerX]);
    const float smileR = avg2(u[MouthCornerPullRight], u[MouthCornerSlantRight]);
    const float smileL = avg2(u[MouthCornerPullLeft], u[MouthCornerSlantLeft]);
    c[MouthSmileRight] = smileR;
    c[MouthSmileLeft] = smileL;
    c[MouthSadRight] = std::max(u[MouthFrownRight], u[MouthStretchRight]);
    c[MouthSadLeft] = std::max(u[MouthFrownLeft], u[MouthStretchLeft]);
    c[SmileFrownRight] = smileR - u[MouthFrownRight];
    c[SmileFrownLeft] = smileL - u[MouthFrownLeft];
    c[SmileFrown] = avg2(c[SmileFrownRight], c[SmileFrownLeft]);
    c[SmileSadRight] = smileR - c[MouthSadRight];
    c[SmileSadLeft] = smileL - c[MouthSadLeft];
    c[SmileSad] = avg2(c[SmileSadRight], c[SmileSadLeft]);
    c[MouthStretch] = avg2(u[MouthStretchLeft], u[MouthStretchRight]);
    c[MouthTightener] = avg2(u[MouthTightenerLeft], u[MouthTightenerRight]);
    c[MouthPress] = avg2(u[MouthPressLeft], u[MouthPressRight]);
    c[MouthDimple] = avg2(u[MouthDimpleLeft], u[MouthDimpleRight]);
    c[TongueX] = u[TongueRight] - u[TongueLeft];
    c[TongueY] = u[TongueUp] - u[TongueDown];
    c[TongueArchY] = u[TongueCurlUp] - u[TongueBendDown];
    c[TongueShape] = u[TongueFlat] - u[TongueSquish];
    for (int i = kUeCount; i < kSlotEyeActive; ++i) v[i] = std::clamp(v[i], -1.0f, 1.0f);
    v[kSlotEyeActive] = 1.0f;
    v[kSlotLipActive] = 1.0f;
}

void flush() {
    if (gBundleLen > 16) {
        sendRaw(gBundle, gBundleLen);
    }
    gBundleLen = 0;
}

inline void beginBundle() {
    std::memcpy(gBundle, "#bundle\0", 8);
    const uint8_t immediate[8] = {0, 0, 0, 0, 0, 0, 0, 1};
    std::memcpy(gBundle + 8, immediate, 8);
    gBundleLen = 16;
}

// Appends one message: address + typetag (+ optional float). Starts a new datagram when full.
void append(int addrOff, int addrLen, char tag, float value) {
    if (addrOff < 0) return;
    const int msgLen = addrLen + 4 + (tag == 'f' ? 4 : 0);
    if (gBundleLen + 4 + msgLen > static_cast<int>(sizeof(gBundle))) {
        flush();
        beginBundle();
    }
    if (gBundleLen == 0) beginBundle();
    const uint32_t be = htonl(static_cast<uint32_t>(msgLen));
    std::memcpy(gBundle + gBundleLen, &be, 4);
    uint8_t* m = gBundle + gBundleLen + 4;
    std::memcpy(m, gAddrPool + addrOff, addrLen);
    m[addrLen] = ',';
    m[addrLen + 1] = static_cast<uint8_t>(tag);
    m[addrLen + 2] = 0;
    m[addrLen + 3] = 0;
    if (tag == 'f') putFloatBE(m + addrLen + 4, value);
    gBundleLen += 4 + msgLen;
    gMessagesSent.fetch_add(1, std::memory_order_relaxed);
}

// Sends changed parameters (all of them once per second, so late joiners / resets recover).
void send(const float* u, int64_t now) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gEnabled || gActiveSlots.load(std::memory_order_relaxed) == 0) return;
    compute(u);
    const bool full = now - gLastFullNs > 1000000000LL;
    if (full) gLastFullNs = now;
    gBundleLen = 0;
    for (int s = 0; s < kSlotCount; ++s) {
        const SlotCfg& c = gCfg[s];
        if (!c.hasFloat && c.bits == 0 && !c.hasNeg) continue;
        const float val = gValue[s];
        if (s >= kSlotEyeActive) {  // status bool
            if (full) append(c.floatAddr, c.floatLen, 'T', 0.0f);
            continue;
        }
        if (c.hasFloat && (full || std::fabs(val - gLastFloat[s]) > 0.004f)) {
            append(c.floatAddr, c.floatLen, 'f', val);
            gLastFloat[s] = val;
        }
        if (c.bits > 0 || c.hasNeg) {
            // VRCFT binary sync: |value| quantized to (2^bits - 1) steps, sign in <Name>Negative.
            const int steps = (1 << c.bits) - 1;
            const float mag = std::min(std::fabs(val), 1.0f);
            const int q = c.bits > 0 ? static_cast<int>(mag * steps + 0.5f) : 0;
            const int packed = q | ((c.hasNeg && val < 0.0f) ? (1 << 16) : 0);
            const int last = gLastBits[s];
            for (int b = 0; b < c.bits; ++b) {
                const int bit = (q >> b) & 1;
                if (full || last < 0 || ((last >> b) & 1) != bit) append(c.bitAddr[b], c.bitLen[b], bit ? 'T' : 'F', 0.0f);
            }
            if (c.hasNeg) {
                const int neg = (packed >> 16) & 1;
                if (full || last < 0 || ((last >> 16) & 1) != neg) append(c.negAddr, c.negLen, neg ? 'T' : 'F', 0.0f);
            }
            gLastBits[s] = packed;
        }
    }
    flush();
}

}  // namespace v2
