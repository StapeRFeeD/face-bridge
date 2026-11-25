# Face Bridge — roadmap

Written for v2.1 (2026-11-23) and updated for v2.2.3. Priority: **P1** = most benefit / least risk,
**P2** = useful, **P3** = nice to have. ✅ = done.

## 1. Tracking quality

| # | What | Why | Effort | How to verify |
|---|---|---|---|---|
| 1.1 **P1** | Also read the **eye ring** (SHM kind 2: openness, gaze, timestamp), not only the face ring | Eyes may update faster than the face (~23 Hz): quicker blinks and gaze. Measure the eye ring's rate first | medium | log both rings' rates; blinks in a mirror |
| 1.2 **P1** | Don't smooth eyelids with the same One Euro settings as everything else (separate settings or off) | Smoothing adds latency exactly where it is most visible: blinks | small | host test: a 0→1 step within ≤ 1 frame |
| 1.3 **P2** | Upsample 23 → 60/72 Hz by interpolating between frames | Smoother motion on the avatar (parameters currently change in 23 steps per second). Cost: +1 frame of latency, so make it a switch | medium | mirror comparison; message counter |
| 1.4 **P2** | Calibration: drop the hard Lower ≤ 0.8 limit (resting brows are ~0.8+) and handle "always high" shapes separately | Brows are nearly dead after calibration | small | saved calibration + host test |
| 1.5 **P2** | Recalibrate one expression instead of the whole session; show the result (ranges) | Quick fix for one weak expression | medium | UI + persistence |
| 1.6 **P3** | Verify the native `/tracking/face/v1` path on an avatar with built-in Face Tracking | Implemented but not checked in game | small | avatar with native FT |

## 2. Usability

| # | What | Why | Effort |
|---|---|---|---|
| 2.1 **P1** ✅ | **Automatic start/stop with VRChat**: tracking cameras run only while VRChat is running with a face-tracked avatar | No need to press Start; cameras don't run for nothing → battery (v2.2) | medium |
| 2.2 **P1** | Restore `VisemesEnable` if the bridge process was killed (remember the path, restore on the next start/connection to the same avatar) | After a forced close, visemes currently stay off | small |
| 2.3 **P1** | **Own OSCQuery service** (mDNS `_oscjson._tcp` + HTTP host info) | VRChat would then push parameter changes to us: instant avatar-change reaction instead of polling every 2 s, and **control from the avatar menu in game** (e.g. a "pause tracking" parameter) | medium |
| 2.4 **P2** | Per-avatar profiles: save/restore strength and filters for each `avtr_…` | Different avatars need different settings | small |
| 2.5 **P2** | Notification actions: Stop / Pause, a status line (avatar, fps) | Control without opening the app | small |
| 2.6 **P2** | "Live" page: live face (FaceView) + bars for key expressions after all processing | Shows exactly what goes to VRChat; easier tuning | medium |
| 2.7 **P3** | Interface localization (strings in `res/values-*`) | Users' languages | small |
| 2.8 **P3** | Optional light theme (as in the PICO OS 6 guidelines) | Choice of look | medium |

## 3. Performance and battery

| # | What | Why | Effort |
|---|---|---|---|
| 3.1 **P1** ✅ | Measure CPU/power with VRChat and record a baseline | [power-baseline.md](power-baseline.md) | small |
| 3.2 **P2** | Adaptive ring polling: sleep until the expected next frame instead of a fixed 60 Hz | Fewer CPU wake-ups (frames arrive at ~23 Hz). Partly done: 10 Hz while no frames arrive | small |
| 3.3 **P2** ✅ | No wake lock while tracking is on standby / the headset is off the head | Idle battery (v2.2) | small |
| 3.4 **P3** ✅ | The diagnostic log (1 line/s) only in Developer mode | Minor savings | small |

## 4. Reliability and code

| # | What | Why | Effort |
|---|---|---|---|
| 4.1 **P1** | Move the host tests (OSC packet, ring, filters, calibration, v2 parameters) into the repository (`tests/`, CMake build on the PC) | Without saved tests regressions go unnoticed | small |
| 4.2 **P2** | Split `MainActivity` (pages into separate classes), move UI strings into resources | Easier maintenance and localization | medium |
| 4.3 **P2** | A stable release signing key instead of a machine's debug key | Updates keep working when built on another machine (see [RELEASING.md](RELEASING.md)) | small |
| 4.4 **P3** | Compatibility checks with newer firmware (5.13.7+, OS 6): ring slot size, Binder transactions | Protection against silent breakage after a PICO update | small |
| 4.5 **P3** ✅ | Security review: DUMP-protected adb hooks, OSCQuery accepted only from this headset, input limits | v2.2.3 | small |

## Decision (2026-11-23): v2.2 focuses on optimization and battery

Other features come later, as time allows.

### Step 1. Baseline measurements (before changes)

The main consumer was expected to be PICO's tracking cameras and algorithm (`EyeTrackingService`),
which our `StartAlgorithm` turns on, rather than our process, so both were measured.

| Scenario | Duration |
|---|---|
| A. Headset worn, bridge stopped, VRChat closed (background) | 10 min |
| B. Bridge running, VRChat closed | 10 min |
| C. VRChat without the bridge | 10 min |
| D. VRChat + bridge (normal use) | 10 min |
| E. Bridge running, headset off the head / asleep | 10 min |

Metrics:

- CPU % of our process, `EyeTrackingService` and VRChat (`top`, `/proc/<pid>/stat`);
- per-UID `dumpsys batterystats` (after `--reset`) and the battery level drop per scenario;
- temperature (`dumpsys thermalservice`);
- CPU wake-ups and wake locks (`dumpsys power`);
- VRChat's frame rate (if available), to make sure the bridge doesn't disturb the game.

**Result:** [power-baseline.md](power-baseline.md). The bridge itself uses 1–2 % CPU. The PICO
tracking it turns on costs +1.03 W on the home screen and only +0.47 W (+4.7 %) in VRChat, because
VRChat itself keeps eye tracking on. While asleep, the bridge needlessly woke PICO's service every 5 s.

### Step 2. Optimizations (based on the measurements) — shipped in v2.2

1. Tracking only while needed (2.1): StopAlgorithm while VRChat is not running, the avatar has no
   face tracking parameters or the headset is off the head; automatic resume.
2. No wake lock while tracking is stopped (3.3).
3. An idle native loop (10 Hz) instead of a constant 60 Hz.

### How it was measured

- **Charge:** the headset was charged to 100 %, then the build under test was installed.
- **Connection:** the cable was used once: `adb tcpip 5555` → `adb connect <headset-ip>:5555`, then unplugged.
  Over USB the headset charges, which makes battery measurements meaningless.
- **Recording:** the headset records the data itself with `tools/power_sample.sh` (charge counter,
  voltage, temperature, per-process CPU) into `/data/local/tmp/power_<scenario>.csv`, which is pulled afterwards.
