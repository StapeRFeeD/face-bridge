# Face Bridge — technical notes

Kotlin + C++17 (NDK). No Unity, no root, no OpenXR session.

## Getting the data (the PicoFacialBridge method)

```
pxreyetrackingservice (Binder, pvr.IEyeTrackingService)
  ├─ tx 6  StartAlgorithm(int 5, "12", int 1000)    ← needs EYE_TRACKING + FACE_TRACKING
  ├─ tx 18 GetSharedMemory(kind=3) → ashmem fd     ← facial sample ring (stride 896)
  └─ tx 9  StopAlgorithm(int 5, int 12)
native-lib.cpp: mmap(PROT_READ) → every tick reads the latest published slot (index/timestamp
  check, seqlock-style) → 52 PICO shapes → ARKit → Unified Expressions (102) → sendto(127.0.0.1:9000)
```

The service does not take an OpenXR session, so it keeps working while VRChat has focus. The
supervisor in `OscBridgeService` opens the session only while tracking is wanted (see
[Power](#power)). It reopens the session when the Binder dies, 10 s after frames stop, or after
30 s if no frame ever arrived. Failed opens back off from 5 s to 30 s, and "system is sleep" waits for the
screen-on broadcast.

The ring header (`version, stride, capacity, published index, slot offset`) is validated
against the mapping size before use; an unknown layout is rejected (`face ring rejected`).

Gaze comes from the EyeLook blendshapes (as in UnifiedPicoModule), eyelid closure from EyeBlink.
The eye and face groups are zeroed when PICO marks them invalid (slot offsets 296 / 300). If both
are invalid, nothing is sent and VRChat holds the last frame.

## Two VRChat protocols

| Avatar | What is sent |
|---|---|
| Built-in Face Tracking in the descriptor (`/avatar/avatarfullfacetracking` = T) | blob `/tracking/face/v1` |
| Made for VRCFaceTracking (`…/v2/…` parameters) | parameters `/avatar/parameters/<prefix>v2/<Name>` |

`OscQueryClient` finds VRChat's OSCQuery server through mDNS (`_oscjson._tcp`, `VRChat-Client-…`).
Only a service on the headset's own address is accepted. The client watches `/avatar/change` and
reads the current avatar's parameter list:

- the prefix (`FT/v2/`, `v2/`);
- the binary sync copies (`Name1/2/4…`, `NameNegative`);
- `EyeTrackingActive` / `LipTrackingActive` under any prefix (VRCFT avatars use this toggle to
  switch visemes off while the mouth is tracked).

Only parameters the avatar has are sent: changes, plus the full set once per second, in OSC
bundles. The combined parameters (`EyeLeftX`, `EyeLidLeft` = openness·0.75 + wide·0.25,
`BrowExpression*`, `SmileFrown*`, `JawX`, `MouthX`, `TongueX/Y` …) are computed in
`vrcft_params.inc`. Until VRChat is found, the combined `FT/v2/` set with 3-bit sync is used.

**Visemes.** Some avatars have a `VisemesEnable` menu toggle and don't switch visemes off by
themselves while the mouth is tracked. Microphone visemes and tracked lips then add up, and the
mouth over-drives. With "Turn off avatar visemes while tracking the mouth" (on by default), loading
such an avatar sets `VisemesEnable = false`. It is set back to `true` when the bridge stops or the
option is turned off.

If OSCQuery answers 404 (VRChat is still loading the avatar), the bridge waits and retries. After
three network errors in a row it searches for VRChat again (VRChat uses a new port after a restart).
Responses are capped at 4 MB and the parameter tree at 32 levels.

VRChat on the headset sends nothing to port 9001 (it only reports to OSCQuery services), so the
`avatarfullfacetracking` flag is read through OSCQuery.

## Eyes and gain

- The `/tracking/face/v1` blob drives blendshapes only. VRChat moves the eye bones through the
  separate OSC eye tracking, so every frame also sends `/tracking/eye/LeftRightVec` (gaze vectors
  from the EyeLook shapes, +x right, +y up, +z forward) and `/tracking/eye/EyesClosedAmount`
  (average eyelid closure). After 10 s without data VRChat falls back to auto-look.
- Gain sliders (0.5–3.0×, default 1.5×). "Face" scales every expression except gaze and
  eyelids. "Eye" scales gaze. EyeWide gets an extra ×2 (as in UnifiedPicoModule). Eyelids are not
  scaled, so resting eyes don't look half-closed. "Jaw" (default 1.3×) is independent of Face.

## Power

Measurements: [power-baseline.md](power-baseline.md). The main cost is not the bridge process
(1–2 % CPU) but PICO's tracking (algorithm + IR cameras), which the bridge turns on. So tracking
runs **only while it is needed**:

- the headset is awake (screen on; it resumes right after wake-up, without polling);
- VRChat is running (OSCQuery found VRChat, or its OSC port 9000 is taken; the port check works without Wi-Fi);
- the current avatar has face tracking parameters (VRCFT or native);
- or the calibration screen is open.

Otherwise the bridge is "Standing by": cameras off (StopAlgorithm), wake lock released, native loop at 10 Hz.
The "Track only while VRChat runs" switch is under Status → General (on by default).

PICO limitation: the tracking service counts clients per process and **does not stop** tracking
when a client process is killed without StopAlgorithm (force-stop, reinstall, low memory). That
orphaned tracking draws power until the headset reboots. Normal exits (Stop, the notification's
Stop action, standby) always stop it.

## Interface

The interface follows PICO's spatial design guidelines in a dark theme: 32/24/16 dp radii with
smoothed corners, pill buttons by role, the PICO type scale, Bezier + spring motion, and
calibration audio cues. The full mapping is in [pico-design/README.md](pico-design/README.md).
The base is PICO Sans (the system font) with a left icon navigation (Status · Calibration · Strength ·
Filtering · Developer), a rounded content panel, grouped settings rows, and blue buttons and switches.
The tokens are in `PicoStyle.kt`.

**Developer mode:** tap the "Face Bridge" title 7 times. This adds the Developer page (data
source, OSC eye tracking, a test chatbox message, counters, adb hints). The once-per-second
diagnostic logcat line is only written in this mode.

## Personal calibration (idea from Baballonia)

A Lower/Upper range is stored for each of PICO's 52 raw shapes, and each value is remapped to 0..1
before the crosstalk rules (`calibration.inc`).

"Calibrate" opens `CalibrationActivity` (~2 min, 17 steps): relax, smile, open wide, kiss, puff,
brows up / down, wide eyes, look ←→↑↓, close your eyes, squint, jaw ←→, tongue. Each step shows an
animated mascot (`FaceView`) and a hint, 3 s of "Get ready", then a 2.5–5 s hold. Data is recorded
**only during the hold** (one beep at the start, a double beep at the end). Below the prompt a small
live face mirrors your tracking. Pause (restarts the step), Skip and Cancel are available.

- "Relax": mean → Lower (the resting expression becomes 0).
- Other steps: maximum after an EMA (single-frame spikes are rejected) → Upper, accumulated across steps.
- A shape that barely moved (< 0.15 above rest) is not boosted.
- **Range** (0..1, default 0.5): 0 only removes the resting offset (`(v − lower)/(1 − lower)`),
  1 stretches fully to the recorded maximum. The boost per shape is capped at 2.5×. A full stretch
  over-amplified PICO's lip shapes relative to the jaw.
- After calibration the deadzone becomes 0.03, and the strength sliders are left unchanged.
  Calibration can be turned off or reset.

## Crosstalk filtering

Order: calibration → rules → deadzone → gain → One Euro. Code: `filters.inc`.

| Bit | Rule | Default | Source |
|---|---|---|---|
| 0x001 | MouthClosed ≤ JawOpen | on | VRCFT Correctors |
| 0x002 | LipSuck × (1 − lip raise) | on | VRCFT Correctors |
| 0x004 | Frown × down to 0.5 with an open jaw (> 0.1) | on | UnifiedPicoModule |
| 0x008 | UpperUp/Deepen fade while lips are pressed (Press > 0.1) | on | UnifiedPicoModule |
| 0x010 | Smile fades with a rolled-in lower lip (RollLower > 0.2) | on | UnifiedPicoModule |
| 0x020 | Stretch without a smile → smile/UpperUp × 0.5 | off | UnifiedPicoModule |
| 0x040 | Tongue only with an open mouth (Jaw > 0.25) | on | UnifiedPicoModule |
| 0x080 | BrowDown through a sigmoid (center 0.7): strong frowns only | off | UnifiedPicoModule |
| 0x100 | Tongue hold for 120 ms | on | UnifiedPicoModule |
| 0x200 | One Euro (adaptive smoothing, real dt) | on | VRCFT Data Filter |

Deadzone (0.08): values below the threshold become 0 and the rest is rescaled (VRCFT Parameter
Adjustment); gaze and eyelids are exempt. Smoothing 0..1 maps to a One Euro minimum cutoff of 8…0.5 Hz.

## Data sources

| `source` / `autostart` | Behaviour |
|---|---|
| 0 Auto, 1 PICO service | PICO tracking service (0 is kept for saved settings) |
| 3 Test pattern | synthetic animation to verify the OSC path in VRChat; the cameras stay off |

## Deployment notes

The main screen and the calibration screen carry `pico.vr.position = near` (like the system
Motion Tracker), so PICO opens them as a near panel over VRChat without closing the game (PICO's log
shows `VDM quick allow start near App`). The app is deliberately **not** marked as a VR app
(`pvr.app.type=vr` is absent): PICO OS (`FEAT_SINGLE3D`) force-stops a marked VR app, services
included, when VRChat starts.

`OscBridgeService` and the `.AdbControl` alias are exported only behind
`android.permission.DUMP`, which only adb shell and the system hold.

## Source layout

```
app/build.gradle.kts                      AGP 9.5.2, compileSdk 35, minSdk/targetSdk 29, arm64-v8a, NDK 26.1
app/src/main/AndroidManifest.xml
app/src/main/cpp/CMakeLists.txt           links log, binder_ndk, dl
app/src/main/cpp/native-lib.cpp           Binder + ring mmap, mapping, UDP 9000, 60 Hz loop
app/src/main/cpp/vrcft_params.inc         VRCFT v2 avatar parameters (allocation-free OSC bundles)
app/src/main/cpp/filters.inc              crosstalk rules, deadzone, One Euro
app/src/main/cpp/calibration.inc          personal Lower/Upper calibration
app/src/main/java/io/github/staperfeed/facebridge/
    NativeBridge.kt                       JNI surface
    PicoTrackingSession.kt                service lookup, Start/StopAlgorithm, shared-memory fd
    OscBridgeService.kt                   foreground service, wake lock, loop thread, tracking supervisor
    OscQueryClient.kt                     VRChat OSCQuery discovery, avatar parameters, visemes
    MainActivity.kt                       control panel; adb hooks via .AdbControl
    CalibrationActivity.kt / FaceView.kt  guided calibration / animated mascot
    Tuning.kt / Filters.kt / Calibration.kt / DevMode.kt / Power.kt   persisted settings
    PicoStyle.kt / SmoothRect.kt / Motion.kt / UiSound.kt   design tokens, smoothed corners, motion, cues
    Screenshots.kt                        adb-only documentation screenshots
tools/make_icon.py                        launcher icon in the PICO OS 5 style (mipmap-*)
tools/make_sounds.py                      calibration cue synthesis (res/raw)
tools/power_sample.sh, analyze_power.py   on-headset power measurement
```

## What has been verified

On the host (clang): the 132-byte packet with header `2f 74 … 31 00 00 00 2c 62 00 00 00 00 00 66`;
VRChat's key order; reading a synthetic ring (new sample, no re-read of a repeat, validity gating,
rejection of an unknown version); `kPicoToArkit` is a permutation; parsing `/avatar/avatarfullfacetracking`.

On the headset (PICO 4 Pro, PICO OS 5.13.3, API 29):

- `pxreyetrackingservice` is found through the NDK binder, and StartAlgorithm returns 0 on an awake headset.
- The ring maps with `stride=896 capacity=5`, and the test pattern sends exactly 60 packets/s with no `sendto` errors.
- While worn: both groups are valid at ~23 frames/s with a frame age of 33–78 ms. Reference expressions
  (jaw, left/right eye closure separately, smile, gaze left/right) land in the right keys and sides.
- The bridge runs in the background with VRChat in front, and VRChat's socket on 9000 reads the
  packets without drops.
- In VRChat (v2.2.3): tracking starts on a VRCFT avatar (43 parameters), goes to standby on an avatar without face
  tracking (PICO tracking CPU 55 % → 3.5 %), and stops when VRChat closes. A spoofed
  `VRChat-Client` mDNS service on the LAN is ignored.

## Deviations from the original design

- Binder + shared memory (the PicoFacialBridge method) instead of `libpxr_api.so` / OpenXR.
- Key order from VRChat's official page: Dilation/Constrict = 10–13, JawMandibleRaise = 42,
  MouthClosed = 43, NoseSneer = 62/63, TongueOut = 86, 99–101 = ThroatSwallow/NeckFlex.
- `MouthUpperDeepen*` = `MouthUpperUp*` (as in UnifiedPicoModule).
- No `package=` in the manifest (AGP sets it through `namespace`).
