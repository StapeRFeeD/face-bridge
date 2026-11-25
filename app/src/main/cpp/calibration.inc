// calibration.inc — per-expression personal calibration (idea from Project Babble's Baballonia:
// per-expression Lower/Upper range remapped to 0..1, with auto-calibration while making faces).
// Re-implemented here; no code copied. Operates on the 52 raw shapes in ARKit order, before the
// crosstalk rules. Static storage only; recording and applying run inside the tracking loop.
//
// Guided session (driven from Kotlin):
//   phase NEUTRAL      relaxed face: per-shape mean            -> lower (resting value becomes 0)
//   phase EXPRESSIONS  make every expression: spike-filtered max -> upper (real maximum becomes 1)
// A shape that never moved clearly above its resting value keeps upper = 1 (offset only, no boost).

namespace calib {

enum Phase : int { IDLE = 0, NEUTRAL = 1, EXPRESSIONS = 2, EXPRESSIONS_CONTINUE = 3 };
// IDLE pauses recording without discarding data; EXPRESSIONS starts a fresh maximum,
// EXPRESSIONS_CONTINUE resumes it (step-by-step sessions record only during each hold).

std::mutex gMutex;
std::atomic<bool> gEnabled{false};
// 0 = only remove the resting offset (rescale lower..1 -> 0..1), 1 = full lower..upper stretch.
std::atomic<float> gRange{0.5f};
constexpr float kMaxBoost = 2.5f;    // full stretch never amplifies a shape more than this
float gLower[kArkitCount];
float gUpper[kArkitCount];

std::atomic<int> gPhase{IDLE};
double gSum[kArkitCount];
int gCount = 0;
float gEma[kArkitCount];
float gMax[kArkitCount];
int gExprFrames = 0;
bool gEmaPrimed = false;

constexpr float kMinSpan = 0.15f;    // movement needed above rest to calibrate the maximum
constexpr float kEmaAlpha = 0.35f;   // rejects single-frame spikes when tracking the maximum

alignas(16) float gOut[kArkitCount];

void beginPhase(int phase) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (phase == NEUTRAL) {
        for (int i = 0; i < kArkitCount; ++i) gSum[i] = 0.0;
        gCount = 0;
    } else if (phase == EXPRESSIONS) {
        for (int i = 0; i < kArkitCount; ++i) { gEma[i] = 0.0f; gMax[i] = 0.0f; }
        gExprFrames = 0;
    } else if (phase == EXPRESSIONS_CONTINUE) {
        gEmaPrimed = false;  // restart the spike filter at each hold, keep the maxima
        gPhase.store(EXPRESSIONS);
        return;
    }
    gEmaPrimed = false;
    gPhase.store(phase);
}

// Called with a fresh, valid raw frame (ARKit order).
void record(const float* a) {
    const int phase = gPhase.load(std::memory_order_relaxed);
    if (phase == IDLE) return;
    std::lock_guard<std::mutex> lock(gMutex);
    if (phase == NEUTRAL) {
        for (int i = 0; i < kArkitCount; ++i) gSum[i] += a[i];
        ++gCount;
    } else {
        for (int i = 0; i < kArkitCount; ++i) {
            gEma[i] = !gEmaPrimed ? a[i] : gEma[i] + kEmaAlpha * (a[i] - gEma[i]);
            gMax[i] = std::max(gMax[i], gEma[i]);
        }
        gEmaPrimed = true;
        ++gExprFrames;
    }
}

// Ends the session and computes lower/upper. Returns false when too little data was recorded.
bool finish(float* lowerOut, float* upperOut) {
    std::lock_guard<std::mutex> lock(gMutex);
    gPhase.store(IDLE);
    if (gCount < 20 || gExprFrames < 40) return false;
    for (int i = 0; i < kArkitCount; ++i) {
        const float rest = std::clamp(static_cast<float>(gSum[i] / gCount), 0.0f, 0.8f);
        const float peak = std::clamp(gMax[i], 0.0f, 1.0f);
        lowerOut[i] = rest;
        upperOut[i] = peak - rest >= kMinSpan ? peak : 1.0f;
    }
    return true;
}

void set(const float* lower, const float* upper, bool enabled) {
    std::lock_guard<std::mutex> lock(gMutex);
    for (int i = 0; i < kArkitCount; ++i) {
        const float lo = std::clamp(lower[i], 0.0f, 0.9f);
        gLower[i] = lo;
        gUpper[i] = std::clamp(upper[i], lo + 0.05f, 1.0f);
    }
    gEnabled.store(enabled);
}

// Returns the calibrated frame (static buffer) or the input when calibration is off.
const float* apply(const float* a) {
    if (!gEnabled.load(std::memory_order_relaxed)) return a;
    std::lock_guard<std::mutex> lock(gMutex);
    const float k = std::clamp(gRange.load(std::memory_order_relaxed), 0.0f, 1.0f);
    for (int i = 0; i < kArkitCount; ++i) {
        const float lo = gLower[i];
        const float d = a[i] - lo;
        const float offsetOnly = d / std::max(1.0f - lo, 0.05f);
        const float full = d / std::max(gUpper[i] - lo, 1.0f / kMaxBoost);
        const float v = offsetOnly + k * (full - offsetOnly);
        gOut[i] = std::isfinite(v) ? std::clamp(v, 0.0f, 1.0f) : 0.0f;
    }
    return gOut;
}

}  // namespace calib
