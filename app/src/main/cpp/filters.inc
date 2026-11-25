// filters.inc — crosstalk suppression and smoothing for PICO expressions.
// Included by native-lib.cpp before mapToUnified(). Static state only (hot path, no allocation).
//
// Sources of the techniques:
//  * VRCFaceTracking (github.com/benaclejames/VRCFaceTracking, Apache-2.0): Correctors
//    (MouthClosed <= JawOpen, LipSuck limiter), Parameter Adjustment (range floor = deadzone),
//    Data Filter (One Euro filter).
//  * UnifiedPicoModule (github.com/WolalaQAQ/UnifiedPicoModule, MIT): PICO-specific contextual
//    rules (frown/jaw attenuation, upper-lip/press gate, smile/roll suppression, stretch
//    competition, brow-down response, tongue jaw gate and hold), same smoothstep transitions.
//    Conditions read the raw PICO values; adjustments apply to the Unified Expressions.

namespace filt {

enum Flag : int {
    MOUTH_CLOSED_CLAMP = 1 << 0,
    LIP_SUCK_LIMITER   = 1 << 1,
    FROWN_JAW          = 1 << 2,
    UPPER_UP_PRESS     = 1 << 3,
    SMILE_ROLL         = 1 << 4,
    STRETCH_SMILE      = 1 << 5,
    TONGUE_JAW         = 1 << 6,
    BROW_DOWN_CURVE    = 1 << 7,
    TONGUE_HOLD        = 1 << 8,
    ONE_EURO           = 1 << 9,
};
constexpr int kDefaultFlags = MOUTH_CLOSED_CLAMP | LIP_SUCK_LIMITER | FROWN_JAW | UPPER_UP_PRESS |
                              SMILE_ROLL | TONGUE_JAW | TONGUE_HOLD | ONE_EURO;

std::atomic<int> gFlags{kDefaultFlags};
std::atomic<float> gDeadzone{0.08f};     // face expressions below this read as 0, rest rescaled
std::atomic<float> gSmoothing{0.3f};     // 0 = off ... 1 = heavy (maps to One Euro min cutoff)

// Smoothstep ramp 0 -> 1 across [threshold - width/2, threshold + width/2] (UnifiedPicoModule Above()).
inline float above(float v, float threshold, float width) {
    const float lo = std::max(0.0f, threshold - width * 0.5f);
    const float hi = std::min(1.0f, threshold + width * 0.5f);
    if (hi <= lo) return v > threshold ? 1.0f : 0.0f;
    const float x = std::clamp((v - lo) / (hi - lo), 0.0f, 1.0f);
    return x * x * (3.0f - 2.0f * x);
}
inline float below(float v, float threshold, float width) { return 1.0f - above(v, threshold, width); }

inline float normalizedSigmoid(float v, float center, float slope) {
    auto sig = [](double x) { return 1.0 / (1.0 + std::exp(-x)); };
    const double x = std::clamp(v, 0.0f, 1.0f);
    const double lo = sig(-slope * center), hi = sig(slope * (1.0 - center));
    return static_cast<float>((sig(slope * (x - center)) - lo) / (hi - lo));
}

// Tongue hold state.
float gTongueHigh = 0.0f;
bool gTongueWasHigh = false;
int64_t gTongueDeadline = 0;

// Contextual rules: a = raw ARKit-order input, u = Unified Expressions (modified in place).
void applyRules(const float* a, float* u, int flags, int64_t now) {
    if (flags & MOUTH_CLOSED_CLAMP) u[MouthClosed] = std::min(u[MouthClosed], u[JawOpen]);
    if (flags & LIP_SUCK_LIMITER) {
        u[LipSuckLowerLeft] *= 1.0f - std::clamp(u[MouthLowerDownLeft], 0.0f, 1.0f);
        u[LipSuckLowerRight] *= 1.0f - std::clamp(u[MouthLowerDownRight], 0.0f, 1.0f);
        u[LipSuckUpperLeft] *= 1.0f - std::clamp(u[MouthUpperUpLeft], 0.0f, 1.0f);
        u[LipSuckUpperRight] *= 1.0f - std::clamp(u[MouthUpperUpRight], 0.0f, 1.0f);
    }
    if (flags & FROWN_JAW) {  // open jaw makes PICO report frown
        const float m = 1.0f - 0.5f * above(a[jawOpen], 0.1f, 0.1f);
        u[MouthFrownLeft] *= m;
        u[MouthFrownRight] *= m;
    }
    if (flags & UPPER_UP_PRESS) {  // pressed lips leak into upper-lip raise
        const float ml = 1.0f - above(a[mouthPressLeft], 0.1f, 0.1f);
        const float mr = 1.0f - above(a[mouthPressRight], 0.1f, 0.1f);
        u[MouthUpperUpLeft] *= ml;  u[MouthUpperDeepenLeft] *= ml;
        u[MouthUpperUpRight] *= mr; u[MouthUpperDeepenRight] *= mr;
    }
    if (flags & SMILE_ROLL) {  // rolled-in lower lip leaks into smile
        const float m = 1.0f - above(a[mouthRollLower], 0.2f, 0.1f);
        u[MouthCornerPullLeft] *= m;  u[MouthCornerSlantLeft] *= m;
        u[MouthCornerPullRight] *= m; u[MouthCornerSlantRight] *= m;
    }
    if (flags & STRETCH_SMILE) {  // stretch without a real smile: damp leaked smile/upper-lip
        const float wl = above(a[mouthStretchLeft], 0.05f, 0.1f) * below(a[mouthSmileLeft], 0.4f, 0.1f);
        const float wr = above(a[mouthStretchRight], 0.05f, 0.1f) * below(a[mouthSmileRight], 0.4f, 0.1f);
        u[MouthCornerPullLeft] *= 1.0f - 0.5f * wl;  u[MouthCornerSlantLeft] *= 1.0f - 0.5f * wl;
        u[MouthUpperUpLeft] *= 1.0f - 0.5f * wl;     u[MouthUpperDeepenLeft] *= 1.0f - 0.5f * wl;
        u[MouthCornerPullRight] *= 1.0f - 0.5f * wr; u[MouthCornerSlantRight] *= 1.0f - 0.5f * wr;
        u[MouthUpperUpRight] *= 1.0f - 0.5f * wr;    u[MouthUpperDeepenRight] *= 1.0f - 0.5f * wr;
    }
    if (flags & BROW_DOWN_CURVE) {  // squint/blink leak into brow-down: only strong values pass
        const float l = normalizedSigmoid(a[browDownLeft], 0.7f, 10.0f);
        const float r = normalizedSigmoid(a[browDownRight], 0.7f, 10.0f);
        u[BrowLowererLeft] = u[BrowPinchLeft] = l;
        u[BrowLowererRight] = u[BrowPinchRight] = r;
    }
    if (flags & TONGUE_HOLD) {  // bridge short dropouts of a tongue that is out
        const float t = u[TongueOut];
        if (t > 0.15f) {
            gTongueHigh = t;
            gTongueWasHigh = true;
            gTongueDeadline = 0;
        } else if (gTongueWasHigh || (gTongueDeadline != 0 && now < gTongueDeadline)) {
            if (gTongueWasHigh) { gTongueWasHigh = false; gTongueDeadline = now + 120000000LL; }
            if (now < gTongueDeadline) u[TongueOut] = gTongueHigh;
            else gTongueDeadline = 0;
        }
    }
    if (flags & TONGUE_JAW) u[TongueOut] *= above(a[jawOpen], 0.25f, 0.1f);  // tongue needs an open mouth
}

// Deadzone: v -> max(0, v - dz) / (1 - dz) for face expressions (not gaze, not eyelids).
void applyDeadzone(float* u) {
    const float dz = std::clamp(gDeadzone.load(std::memory_order_relaxed), 0.0f, 0.5f);
    if (dz <= 0.0f) return;
    const float scale = 1.0f / (1.0f - dz);
    for (int i = EyeDilationRight; i < kUeCount; ++i) u[i] = std::max(0.0f, u[i] - dz) * scale;
}

// One Euro filter (Casiez et al.), as in VRCFT's Data Filter but with the real frame interval.
struct Euro {
    float x = 0.0f, dx = 0.0f;
    bool init = false;
};
Euro gEuro[kUeCount];
int64_t gLastEuroNs = 0;

inline float alpha(float cutoff, float dt) {
    const float tau = 1.0f / (2.0f * 3.14159265f * cutoff);
    return 1.0f / (1.0f + tau / dt);
}

void applyOneEuro(float* u, int64_t now) {
    const float s = std::clamp(gSmoothing.load(std::memory_order_relaxed), 0.0f, 1.0f);
    float dt = gLastEuroNs == 0 ? 0.0f : static_cast<float>(now - gLastEuroNs) * 1e-9f;
    gLastEuroNs = now;
    if (s <= 0.0f || dt <= 0.0f || dt > 0.5f) {  // off, first frame or after a gap: pass through, reset
        for (int i = 0; i < kUeCount; ++i) { gEuro[i].x = u[i]; gEuro[i].dx = 0.0f; gEuro[i].init = true; }
        return;
    }
    // smoothing 0..1 -> min cutoff 8 Hz .. 0.5 Hz (log scale); beta lets fast motion through.
    const float minCutoff = 8.0f * std::pow(0.0625f, s);
    const float beta = 4.0f;
    const float dCutoff = 1.0f;
    const float aD = alpha(dCutoff, dt);
    for (int i = 0; i < kUeCount; ++i) {
        Euro& e = gEuro[i];
        if (!e.init) { e.x = u[i]; e.dx = 0.0f; e.init = true; continue; }
        const float dxRaw = (u[i] - e.x) / dt;
        e.dx = e.dx + aD * (dxRaw - e.dx);
        const float cutoff = minCutoff + beta * std::fabs(e.dx);
        e.x = e.x + alpha(cutoff, dt) * (u[i] - e.x);
        u[i] = e.x;
    }
}

}  // namespace filt
